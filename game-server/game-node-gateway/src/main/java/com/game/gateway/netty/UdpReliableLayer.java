package com.game.gateway.netty;

import com.xs.net.transport.PacketCodec;
import com.xs.net.transport.PacketFrame;
import com.xs.net.transport.PacketId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 轻量可靠 UDP 层（服务端/客户端对称逻辑）。
 * <p>
 * 帧格式（一个大 datagram 一个包，大端序）：
 * <pre>
 *  偏移 长度 字段        说明
 *  length(4) | packetId(2) | sendSeq(4) | random(4) |
 *  headCRC(2) | bodyCRC(2) | body(N)
 * </pre>
 * 发送方维护在途窗口，超时重传未确认包；接收方按 seq 有序交付并回累积 ACK。
 */
public final class UdpReliableLayer {

    public static final byte TYPE_DATA = (byte) PacketId.DATA;
    public static final byte TYPE_ACK = (byte) PacketId.ACK;
    public static final byte TYPE_HELLO = (byte) PacketId.HELLO;
    public static final byte TYPE_HEARTBEAT = (byte) PacketId.HEARTBEAT;
    public static final byte TYPE_ERROR = (byte) PacketId.ERROR;
    public static final int HEADER_LENGTH = PacketCodec.HEADER_BYTES;

    /** 发送底层字节（由调用方持有 channel/远端地址并真正写出）。 */
    public interface PacketSender {
        void send(byte packetId, long seq, long random, byte[] payload);
    }

    private final int window;
    private final PacketSender sender;
    private final Consumer<byte[]> onDeliver;
    private final LongSupplier nowMs;
    private final long random;

    // 发送方向：在途窗口
    private long sendSeq = 1;
    private final ConcurrentNavigableMap<Long, byte[]> inFlight = new ConcurrentSkipListMap<>();

    // 接收方向：有序交付
    private long nextRecvSeq = 1;
    private final ConcurrentSkipListMap<Long, byte[]> reorder = new ConcurrentSkipListMap<>();
    private long highestAck = 0;

    public UdpReliableLayer(int window, long random, PacketSender sender, Consumer<byte[]> onDeliver, LongSupplier nowMs) {
        this.window = window;
        this.random = random;
        this.sender = sender;
        this.onDeliver = onDeliver;
        this.nowMs = nowMs;
    }

    /** 发送一条业务消息：分配 seq 并入在途窗口，组 DATA 包发出。 */
    public void send(byte[] payload) {
        if (inFlight.size() >= window) {
            // 窗口满：丢弃最旧以腾出空间（避免无限积压），通常上层心跳/重传会自愈
            var first = inFlight.firstKey();
            inFlight.remove(first);
        }
        long seq = sendSeq++;
        inFlight.put(seq, payload);
        sender.send(TYPE_DATA, seq, random, payload);
    }

    /** 解析收到的 datagram，非法返回 null。 */
    public static Packet decode(ByteBuf buf) {
        if (buf.readableBytes() < HEADER_LENGTH) {
            return null;
        }
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        try {
            PacketFrame packet = PacketCodec.decode(bytes);
            return new Packet((byte) packet.packetId(), packet.sendSeq(), packet.random(), packet.body());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 构建一个包为 ByteBuf。 */
    public static ByteBuf encode(byte type, long seq, long ack, byte[] payload) {
        return Unpooled.wrappedBuffer(PacketCodec.encode(
                Byte.toUnsignedInt(type), seq, ack, payload == null ? new byte[0] : payload));
    }

    /** 处理收到的对端包：DATA 按序交付并回 ACK；ACK 移除已确认在途；其它类型仅刷新 ack。 */
    public void onPacket(Packet packet) {
        if (packet.type == TYPE_ACK && packet.seq > 0) {
            long boundary = packet.seq;
            inFlight.headMap(boundary + 1).clear();
        }
        if (packet.type == TYPE_DATA) {
            handleData(packet.seq, packet.payload);
            // 回累积 ACK，让对端推进在途窗口
            sender.send(TYPE_ACK, contiguous(), random, null);
        }
    }

    private void handleData(long seq, byte[] payload) {
        if (seq < nextRecvSeq) {
            // 重复包：丢弃（ACK 已在 onPacket 中回执）
            return;
        }
        if (seq == nextRecvSeq) {
            deliver(seq, payload);
            // 补发乱序缓存中可连序的部分
            while (true) {
                var entry = reorder.firstEntry();
                if (entry == null || entry.getKey() != nextRecvSeq) {
                    break;
                }
                reorder.remove(entry.getKey());
                deliver(entry.getKey(), entry.getValue());
            }
        } else {
            // 乱序：缓存待前序到达后补序
            reorder.putIfAbsent(seq, payload);
            // 防止乱序缓存无限增长，超窗口丢弃
            while (reorder.size() > window * 2) {
                reorder.pollLastEntry();
            }
        }
    }

    private void deliver(long seq, byte[] payload) {
        nextRecvSeq = seq + 1;
        highestAck = Math.max(highestAck, seq);
        onDeliver.accept(payload);
    }

    /** 当前已连续收到的最大 seq（用于累积 ACK）。 */
    public long contiguous() {
        return highestAck;
    }

    /** 重传所有未确认在途包（由定时器调用）。 */
    public void retryOutstanding() {
        for (var e : inFlight.entrySet()) {
            sender.send(TYPE_DATA, e.getKey(), random, e.getValue());
        }
    }

    public record Packet(byte type, long seq, long random, byte[] payload) {
    }
}
