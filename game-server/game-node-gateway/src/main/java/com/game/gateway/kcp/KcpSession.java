package com.game.gateway.kcp;

import com.game.gateway.netty.ClientEndpoint;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * KCP 会话：代表一个已认证的客户端 KCP 端点。
 * <p>承载一个标准 KCP 实例 {@link Kcp}，以远端地址为写出口向客户端回发
 * （channel.writeAndFlush(new DatagramPacket(buf, remote))）；{@link Kcp#update}
 * 由外部（{@link KcpServer} 后台定时线程）驱动。实现 {@link ClientEndpoint} 供下行 S2C 使用。</p>
 */
@Slf4j
public final class KcpSession implements ClientEndpoint {

    private final InetSocketAddress remote;
    private final DatagramChannel channel;
    private final KcpConfig config;
    private final Kcp kcp;
    private volatile String channelId;
    private volatile boolean authenticated;
    private volatile long lastActiveMs;

    public KcpSession(String channelId, InetSocketAddress remote, DatagramChannel channel, KcpConfig config) {
        this.channelId = channelId;
        this.remote = remote;
        this.channel = channel;
        this.config = config;
        this.lastActiveMs = System.currentTimeMillis();
        this.kcp = new Kcp(1400);
        kcp.setWndsize(config.getWindow(), config.getWindow());
        kcp.setInterval((int) config.getRetryIntervalMs());
        kcp.setNodelay(0, (int) config.getRetryIntervalMs(), 2, 0);
        kcp.setOutput((data, size) -> {
            byte[] payload = new byte[size];
            System.arraycopy(data, 0, payload, 0, size);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(payload), remote));
        });
    }

    /** 从网关 DatagramChannel 收到一个 datagram：交给 KCP 输入并驱动协议，交付判读由 AuthHandler 完成。 */
    public void receive(byte[] datagram) {
        lastActiveMs = System.currentTimeMillis();
        kcp.input(datagram);
        kcp.update(System.currentTimeMillis());
    }

    /** 取出 KCP 已组装好的一条可交付应用层消息；无则返回 null。 */
    public byte[] recvDelivered() {
        return kcp.recv();
    }

    /** 外部定时器驱动 KCP 演进（超时重传 / 窗口更新 / 节点间链路探活）。 */
    public void updateNow() {
        kcp.update(System.currentTimeMillis());
    }

    /** 向客户端发送一段原始应用层字节（走 KCP 可靠层）。 */
    public void sendBytes(byte[] data) {
        lastActiveMs = System.currentTimeMillis();
        kcp.send(data);
        kcp.update(System.currentTimeMillis());
    }

    @Override
    public void write(NetMessage message) {
        sendBytes(message.toByteArray());
    }

    /** 认证通过时写入 channelId 并标记已认证；未认证阶段 channelId 为 null。 */
    public void authenticateAs(String channelId) {
        this.channelId = channelId;
        this.authenticated = true;
        this.lastActiveMs = System.currentTimeMillis();
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public String toString() {
        return "KcpSession{channelId=" + channelId + ", remote=" + remote + ", authenticated=" + authenticated + '}';
    }

    @Override
    public boolean isActive() {
        return (System.currentTimeMillis() - lastActiveMs)
                < config.getSessionIdleTimeoutSeconds() * 1000L;
    }

    @Override
    public void close() {
        // UDP 无连接；发送一个空载荷标记会话关闭，客户端可据此感知
        try {
            sendBytes(new byte[0]);
        } catch (Exception ignored) {
            // 会话已失效，忽略发送失败
        }
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

    public InetSocketAddress remote() {
        return remote;
    }

    public Kcp kcp() {
        return kcp;
    }
}
