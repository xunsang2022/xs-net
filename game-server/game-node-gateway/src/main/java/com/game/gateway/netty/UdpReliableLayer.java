package com.game.gateway.netty;

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
 *  0    2   magic      0x58 0x53 ("XS")
 *  2    1   type       1=DATA 2=ACK 3=HELLO 4=HEARTBEAT 5=ERROR
 *  3    4   seq        发送方递增序列号（DATA 携带）
 *  7    4   ack        累积确认：对端已收到的最大连续 seq
 *  11   4   payloadLen payload 字节数
 *  15   -   payload    DATA=NetMessage 字节 / HELLO=JWT
 * </pre>
 * 发送方维护在途窗口，超时重传未确认包；接收方按 seq 有序交付并回累积 ACK。
 */
public final class UdpReliableLayer {

    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_ACK = 2;
    public static final byte TYPE_HELLO = 3;
    public static final byte TYPE_HEARTBEAT = 4;
    public static final byte TYPE_ERROR = 5;

    public static final int OFF_MAGIC = 0;
    public static final int OFF_TYPE = 2;
    public static final int OFF_SEQ = 3;
    public static final int OFF_ACK = 7;
    public static final int OFF_LEN = 11;
    public static final int HEADER_LENGTH = 15;

    private static final byte MAGIC_0 = 0x58;
    private static final byte MAGIC_1 = 0x53;

    /** 发送底层字节（由调用方持有 channel/远端地址并真正写出）。 */
    public interface PacketSender {
        void send(byte type, long seq, long ack, byte[] payload);
    }

    private final int window;
    private final PacketSender sender;
    private final Consumer<byte[]> onDeliver;
    private final LongSupplier nowMs;

    // 发送方向：在途窗口
    private long sendSeq = 1;
    private final ConcurrentNavigableMap<Long, byte[]> inFlight = new ConcurrentSkipListMap<>();

    // 接收方向：有序交付
    private long nextRecvSeq = 1;
    private final ConcurrentSkipListMap<Long, byte[]> reorder = new ConcurrentSkipListMap<>();
    private long highestAck = 0;

    public UdpReliableLayer(int window, PacketSender sender, Consumer<byte[]> onDeliver, LongSupplier nowMs) {
        this.window = window;
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
        sender.send(TYPE_DATA, seq, highestAck, payload);
    }

    /** 解析收到的 datagram，非法返回 null。 */
    public static Packet decode(ByteBuf buf) {
        if (buf.readableBytes() < HEADER_LENGTH) {
            return null;
        }
        int magic0 = buf.getUnsignedByte(OFF_MAGIC);
        int magic1 = buf.getUnsignedByte(OFF_MAGIC + 1);
        if (magic0 != MAGIC_0 || magic1 != MAGIC_1) {
            return null;
        }
        byte type = buf.getByte(OFF_TYPE);
        long seq = buf.getInt(OFF_SEQ) & 0xFFFFFFFFL;
        long ack = buf.getInt(OFF_ACK) & 0xFFFFFFFFL;
        int len = buf.getInt(OFF_LEN);
        byte[] payload = null;
        if (len > 0 && buf.readableBytes() >= HEADER_LENGTH + len) {
            payload = new byte[len];
            buf.getBytes(HEADER_LENGTH, payload);
        }
        return new Packet(type, seq, ack, payload);
    }

    /** 构建一个包为 ByteBuf。 */
    public static ByteBuf encode(byte type, long seq, long ack, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        ByteBuf buf = Unpooled.buffer(HEADER_LENGTH + len);
        buf.writeByte(MAGIC_0);
        buf.writeByte(MAGIC_1);
        buf.writeByte(type);
        buf.writeInt((int) seq);
        buf.writeInt((int) ack);
        buf.writeInt(len);
        if (payload != null) {
            buf.writeBytes(payload);
        }
        return buf;
    }

    /** 处理收到的对端包：DATA 按序交付并回 ACK；ACK 移除已确认在途；其它类型仅刷新 ack。 */
    public void onPacket(Packet packet) {
        // 无论何种包，都携带 ack，据此移除已确认在途
        if (packet.ack > 0) {
            long boundary = packet.ack;
            inFlight.headMap(boundary + 1).clear();
        }
        if (packet.type == TYPE_DATA) {
            handleData(packet.seq, packet.payload);
            // 回累积 ACK，让对端推进在途窗口
            sender.send(TYPE_ACK, 0, contiguous(), null);
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
            sender.send(TYPE_DATA, e.getKey(), highestAck, e.getValue());
        }
    }

    /** 当前在途未确认数量。 */
    public int outstanding() {
        return inFlight.size();
    }

    /** 收到任意对端包后刷新存活（配合心跳/空闲清理）。 */
    public long lastSeenMs(LongSupplier nowMs) {
        return nowMs.getAsLong();
    }

    public record Packet(byte type, long seq, long ack, byte[] payload) {
    }
}
