package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 帧协议：4 字节大端长度前缀 + NetMessage protobuf 字节。
 */
public final class NettyFramer {

    public static final int MAX_FRAME_LENGTH = 65536;
    public static final int LENGTH_FIELD_OFFSET = 0;
    public static final int LENGTH_FIELD_LENGTH = 4;
    public static final int LENGTH_ADJUSTMENT = 0;
    public static final int INITIAL_BYTES_TO_STRIP = 4;

    private NettyFramer() {
    }

    /**
     * 将 NetMessage 编码为「长度前缀 + 消息体」的 ByteBuf。
     */
    public static ByteBuf encode(NetMessage message) {
        byte[] body = message.toByteArray();
        ByteBuf buf = Unpooled.buffer(LENGTH_FIELD_LENGTH + body.length);
        buf.writeInt(body.length);
        buf.writeBytes(body);
        return buf;
    }
}