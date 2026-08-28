package com.game.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 面向网关 UDP（轻量可靠 UDP）的联调客户端，与 Unity SDK 的 UDP 协议保持一致。
 * <p>帧格式（15 字节大端头 + payload）：magic("XS")/type/seq/ack/len，
 * type：1=DATA 2=ACK 3=HELLO 4=HEARTBEAT 5=ERROR。握手发 HELLO(JWT) 等 ACK，之后可靠 DATA 上行。</p>
 * <p>用法：{@code java UdpTestClient --host 127.0.0.1 --udp-port 2002 --token &lt;jwt&gt;}</p>
 */
public final class UdpTestClient implements AutoCloseable {

    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK = 2;
    private static final byte TYPE_HELLO = 3;
    private static final int HEADER_LENGTH = 15;
    private static final byte MAGIC_0 = 0x58;
    private static final byte MAGIC_1 = 0x53;

    private final InetSocketAddress gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentSkipListMap<Long, byte[]> inFlight = new ConcurrentSkipListMap<>();
    private final CountDownLatch handshake = new CountDownLatch(1);

    private DatagramSocket socket;
    private long sendSeq = 1;
    private long nextRecvSeq = 1;
    private long highestAck = 0;
    private volatile boolean authenticated;

    public UdpTestClient(String host, int port) {
        this.gateway = new InetSocketAddress(host, port);
    }

    public void connect(String token) throws Exception {
        socket = new DatagramSocket();
        socket.connect(gateway);
        sendPacket(TYPE_HELLO, 0, 0, token.getBytes(StandardCharsets.UTF_8));
        if (!handshake.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("UDP handshake timeout (no ACK from gateway)");
        }
        System.out.println("UDP authenticated");
    }

    public void send(int rpcNum, Object payload) throws Exception {
        RpcMessageSpec<? extends MessageLite> spec = GameRpcMessages.INSTANCE.find(rpcNum);
        if (spec == null || !spec.publicVisible()) {
            throw new IllegalArgumentException("Unknown or non-public rpc: " + rpcNum);
        }
        Object dto = objectMapper.convertValue(payload, spec.dtoClass());
        MessageLite proto = spec.encode(dto);
        NetMessage message = NetMessage.newBuilder()
                .setRpcNum(rpcNum)
                .setData(ByteString.copyFrom(proto.toByteArray()))
                .build();
        sendReliable(message.toByteArray());
    }

    private void sendReliable(byte[] body) {
        long seq = sendSeq++;
        inFlight.put(seq, body);
        sendPacket(TYPE_DATA, seq, highestAck, body);
    }

    private void sendPacket(byte type, long seq, long ack, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        byte[] bytes = new byte[HEADER_LENGTH + len];
        bytes[0] = MAGIC_0;
        bytes[1] = MAGIC_1;
        bytes[2] = type;
        writeIntBE(bytes, 3, seq);
        writeIntBE(bytes, 7, ack);
        writeIntBE(bytes, 11, len);
        if (len > 0) {
            System.arraycopy(payload, 0, bytes, HEADER_LENGTH, len);
        }
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, gateway));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to send UDP datagram", e);
        }
    }

    /** 阻塞接收并处理一个网关回包；返回已交付的 DATA 消息，其余返回 null。 */
    public NetMessage receiveOnce() throws Exception {
        byte[] buffer = new byte[HEADER_LENGTH + 65536];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        byte[] data = datagram.getData();
        int length = datagram.getLength();
        if (length < HEADER_LENGTH || data[0] != MAGIC_0 || data[1] != MAGIC_1) {
            return null;
        }
        byte type = data[2];
        long seq = readIntBE(data, 3);
        long ack = readIntBE(data, 7);
        int len = (int) readIntBE(data, 11);

        if (ack > 0) {
            inFlight.headMap(ack + 1).clear();
        }
        if (type == TYPE_ACK && !authenticated) {
            authenticated = true;
            handshake.countDown();
            return null;
        }
        if (type == TYPE_DATA && seq == nextRecvSeq) {
            nextRecvSeq = seq + 1;
            highestAck = Math.max(highestAck, seq);
            sendPacket(TYPE_ACK, 0, highestAck, null);
            byte[] body = new byte[len];
            System.arraycopy(data, HEADER_LENGTH, body, 0, len);
            return NetMessage.parseFrom(body);
        }
        // 乱序/其它包：回 ACK 推进对端窗口，本次不交付
        if (type == TYPE_DATA) {
            sendPacket(TYPE_ACK, 0, highestAck, null);
        }
        return null;
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
        }
    }

    private static void writeIntBE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) (value >> 24);
        buf[offset + 1] = (byte) (value >> 16);
        buf[offset + 2] = (byte) (value >> 8);
        buf[offset + 3] = (byte) value;
    }

    private static long readIntBE(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF) << 24)
                | ((long) (buf[offset + 1] & 0xFF) << 16)
                | ((long) (buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 2002;
        String token = "dev-token";
        for (String arg : args) {
            String[] pair = arg.split("=", 2);
            if (pair.length == 2) {
                if (pair[0].equals("--host")) host = pair[1];
                if (pair[0].equals("--udp-port")) port = Integer.parseInt(pair[1]);
                if (pair[0].equals("--token")) token = pair[1];
            }
        }
        try (UdpTestClient client = new UdpTestClient(host, port)) {
            client.connect(token);
            client.send(1000, Map.of("playerId", "1001", "playerName", "Alice", "sessionId", "s1"));
            System.out.println("sent CS_Login (1000), waiting for response...");
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                NetMessage msg = client.receiveOnce();
                if (msg != null) {
                    System.out.println("received rpc=" + msg.getRpcNum() + " data=" + msg.getData());
                    if (msg.getRpcNum() == 1002) {
                        System.out.println("SC_Login received, UDP flow OK");
                        return;
                    }
                }
            }
            System.out.println("timeout waiting for SC_Login");
        }
    }
}
