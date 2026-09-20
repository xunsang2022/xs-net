package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 客户端通道端点：封装 Netty {@link Channel}，下行 S2C 用 {@link NettyFramer} 编码写回。
 */
@Slf4j
public final class TcpClientEndpoint implements ClientEndpoint {

    /** Netty TCP 连接；由 Lombok 生成的构造器保证该依赖不为空。 */
    private final Channel channel;
    private final long connectionRandom;
    private final AtomicLong sendSequence = new AtomicLong(1);

    public TcpClientEndpoint(Channel channel, long connectionRandom) {
        this.channel = channel;
        this.connectionRandom = connectionRandom;
    }

    public TcpClientEndpoint(Channel channel) {
        this(channel, 0);
    }

    @Override
    public void write(NetMessage message) {
        if (channel.isActive() && channel.isWritable()) {
            channel.writeAndFlush(NettyFramer.encodeData(connectionRandom, sendSequence.getAndIncrement(), message));
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

    @Override
    public long connectionRandom() {
        return connectionRandom;
    }

    private String channelId() {
        return channel != null ? channel.id().asLongText() : "?";
    }
}
