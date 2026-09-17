package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP 客户端通道端点：封装 Netty {@link Channel}，下行 S2C 用 {@link NettyFramer} 编码写回。
 */
@Slf4j
@RequiredArgsConstructor
public final class TcpClientEndpoint implements ClientEndpoint {

    /** Netty TCP 连接；由 Lombok 生成的构造器保证该依赖不为空。 */
    @NonNull
    private final Channel channel;

    @Override
    public void write(NetMessage message) {
        if (channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(NettyFramer.encode(message));
        } else {
            log.warn("TCP channel not writable, drop S2C: channelId={}", channelId());
        }
    }

    @Override
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
    }

    @Override
    public String sessionId() {
        return channelId();
    }

    @Override
    public TransportKind kind() {
        return TransportKind.TCP;
    }

    private String channelId() {
        return channel != null ? channel.id().asLongText() : "?";
    }
}
