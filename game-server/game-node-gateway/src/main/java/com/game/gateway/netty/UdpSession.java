package com.game.gateway.netty;

import com.game.gateway.config.UdpConfig;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * UDP 会话：代表一个已认证的客户端 UDP 端点。
 * <p>持有可靠层 {@link UdpReliableLayer}，负责 DATA 可靠收发、ACK 回执、有序交付，
 * 并以远端地址为写出口向客户端回发。实现 {@link ClientEndpoint} 供下行 S2C 使用。</p>
 */
@Slf4j
public final class UdpSession implements ClientEndpoint {

    private final String channelId;
    private final InetSocketAddress remote;
    private final DatagramChannel channel;
    private final UdpReliableLayer layer;
    private final UdpConfig config;
    private final long connectionRandom;
    private volatile long lastActiveMs;

    public UdpSession(String channelId, InetSocketAddress remote, DatagramChannel channel,
                      UdpConfig config, long connectionRandom, Consumer<byte[]> onDeliver) {
        this.channelId = channelId;
        this.remote = remote;
        this.channel = channel;
        this.config = config;
        this.connectionRandom = connectionRandom;
        this.lastActiveMs = System.currentTimeMillis();
        this.layer = new UdpReliableLayer(config.getWindow(), connectionRandom,
                this::sendPacket, onDeliver, System::currentTimeMillis);
    }

    private void sendPacket(byte type, long seq, long ack, byte[] payload) {
        var buf = UdpReliableLayer.encode(type, seq, ack, payload);
        channel.writeAndFlush(new DatagramPacket(buf, remote));
    }

    /** 从网关 DatagramChannel 收到一个 datagram（未认证的 HELLO 由 UdpAuthHandler 直接处理，这里处理已认证会话）。 */
    public void receive(byte[] datagram) {
        lastActiveMs = System.currentTimeMillis();
        var packet = UdpReliableLayer.decode(Unpooled.wrappedBuffer(datagram));
        if (packet != null && packet.random() == connectionRandom) {
            layer.onPacket(packet);
        }
    }

    @Override
    public void write(NetMessage message) {
        lastActiveMs = System.currentTimeMillis();
        layer.send(message.toByteArray());
    }

    @Override
    public boolean isActive() {
        return (System.currentTimeMillis() - lastActiveMs)
                < config.getSessionIdleTimeoutSeconds() * 1000L;
    }

    @Override
    public void close() {
        // UDP 无连接，关闭仅标记失效；发送 ERROR 提示客户端
        try {
            sendPacket(UdpReliableLayer.TYPE_ERROR, 0, layer.contiguous(), null);
        } catch (Exception e) {
            log.warn("Failed to notify UDP session close: channelId={}, remote={}", channelId, remote, e);
        }
    }

    /** 重传未确认在途包（由后台定时器调用）。 */
    public void retryOutstanding() {
        layer.retryOutstanding();
    }

    public String channelId() {
        return channelId;
    }

    @Override
    public String sessionId() {
        return channelId;
    }

    @Override
    public TransportKind kind() {
        return TransportKind.UDP;
    }

    public long lastActiveMs() {
        return lastActiveMs;
    }

    @Override
    public long connectionRandom() {
        return connectionRandom;
    }
}
