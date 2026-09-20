package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.transport.PacketCodec;
import com.xs.net.transport.PacketFrame;
import com.xs.net.transport.PacketId;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * TCP/WebSocket 业务包统一使用 XS packet 头。
 */
public final class NettyFramer {

    public static final int MAX_FRAME_LENGTH = 65536;
    public static final int LENGTH_FIELD_OFFSET = 0;
    public static final int LENGTH_FIELD_LENGTH = 4;
    public static final int LENGTH_ADJUSTMENT = PacketCodec.HEADER_BYTES - Integer.BYTES;
    public static final int INITIAL_BYTES_TO_STRIP = 0;

    private NettyFramer() {
    }

    /** Encode a data packet with a zero logical-session id for codec compatibility. */
    public static ByteBuf encode(NetMessage message) {
        return encode(PacketId.DATA, 0, 0, message.toByteArray());
    }

    public static ByteBuf encode(int packetId, long sendSeq, long random, byte[] body) {
        return Unpooled.wrappedBuffer(PacketCodec.encode(packetId, sendSeq, random, body));
    }

    public static PacketFrame decode(ByteBuf frame) {
        byte[] bytes = new byte[frame.readableBytes()];
        frame.getBytes(frame.readerIndex(), bytes);
        return PacketCodec.decode(bytes);
    }

    public static ByteBuf encodeHello(long random, byte[] body) {
        return encode(PacketId.HELLO, 0, random, body);
    }

    public static ByteBuf encodeAck(long random, long sendSeq) {
        return encode(PacketId.ACK, sendSeq, random, new byte[0]);
    }

    public static ByteBuf encodeHeartbeat(long random, long sendSeq) {
        return encode(PacketId.HEARTBEAT, sendSeq, random, new byte[0]);
    }

    public static ByteBuf encodeData(long random, long sendSeq, NetMessage message) {
        return encode(PacketId.DATA, sendSeq, random, message.toByteArray());
    }

}
