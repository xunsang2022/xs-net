package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import lombok.RequiredArgsConstructor;

/** WebSocket endpoint where every binary frame is a raw NetMessage protobuf body. */
@RequiredArgsConstructor
public final class WebSocketClientEndpoint implements ClientEndpoint {

    private final Channel channel;

    @Override
    public void write(NetMessage message) {
        if (channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.toByteArray())));
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
}
