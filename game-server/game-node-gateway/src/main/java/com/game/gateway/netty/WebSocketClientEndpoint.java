package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.concurrent.atomic.AtomicLong;

/** WebSocket 下行端点，每个二进制帧直接承载 NetMessage Protobuf 数据。 */
public final class WebSocketClientEndpoint implements ClientEndpoint {

    /** 当前 WebSocket 连接。 */
    private final Channel channel;
    private final long connectionRandom;
    private final AtomicLong sendSequence = new AtomicLong(1);

    public WebSocketClientEndpoint(Channel channel, long connectionRandom) {
        this.channel = channel;
        this.connectionRandom = connectionRandom;
    }

    public WebSocketClientEndpoint(Channel channel) {
        this(channel, 0);
    }

    @Override
    public void write(NetMessage message) {
        if (channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(new BinaryWebSocketFrame(
                    NettyFramer.encodeData(connectionRandom, sendSequence.getAndIncrement(), message)));
        }
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public String sessionId() {
        return channel.id().asLongText();
    }

    @Override
    public TransportKind kind() {
        return TransportKind.WEBSOCKET;
    }

    @Override
    public long connectionRandom() {
        return connectionRandom;
    }
}
