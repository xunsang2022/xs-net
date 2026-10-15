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

    /** FEC 分组的最大存活时间：整组丢包过多时无法还原，超时直接丢弃避免堆积。 */
    private static final long FEC_GROUP_MAX_AGE_MS = 5000L;

    private final InetSocketAddress remote;
    private final DatagramChannel channel;
    private final KcpConfig config;
    private final Kcp kcp;
    /** KCP 之上的 XOR 前向纠错层；未启用时为 null。 */
    private final FecCodec fec;
    private volatile String channelId;
    private volatile boolean authenticated;
    private volatile long lastActiveMs;
    /** 是否需要后台节拍驱动：空闲会话置 false，避免每节拍对全部会话加锁。 */
    private volatile boolean maintenancePending = true;

    public KcpSession(int conv, String channelId, InetSocketAddress remote, DatagramChannel channel, KcpConfig config) {
        this.channelId = channelId;
        this.remote = remote;
        this.channel = channel;
        this.config = config;
        this.lastActiveMs = System.currentTimeMillis();
        this.fec = config.getFecK() > 0
                ? new FecCodec(config.getFecK(), config.getFecM(),
                        framed -> channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(framed), remote)))
                : null;
        // 会话号由客户端首包携带：同一源地址上的新连接会使用不同会话号，据此可识别并重建会话
        this.kcp = new Kcp(conv, 1400);
        kcp.setWndsize(config.getWindow(), config.getWindow());
        // 低延迟模式：nodelay=1、节拍=重传间隔、fastresend=2、nocwnd=1（关闭拥塞窗口限制）
        kcp.setNodelay(1, (int) config.getRetryIntervalMs(), 2, 1);
        kcp.setOutput((data, size) -> {
            byte[] payload = new byte[size];
            System.arraycopy(data, 0, payload, 0, size);
            sendDatagram(payload);
        });
    }

    /** 发出一个 KCP 数据报：启用 FEC 时先做分组编码，否则直接写出。 */
    private void sendDatagram(byte[] payload) {
        if (fec == null) {
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(payload), remote));
        } else {
            fec.encode(payload);
        }
    }

    /** 把不足一组的待发分片补齐发出，避免为凑组引入额外时延。 */
    private void flushFec() {
        if (fec != null) {
            fec.flushGroup();
        }
    }

    /** 从网关 DatagramChannel 收到一个 datagram：交给 KCP 输入并驱动协议，交付判读由 AuthHandler 完成。 */
    public void receive(byte[] datagram) {
        lastActiveMs = System.currentTimeMillis();
        maintenancePending = true;
        if (fec == null) {
            kcp.input(datagram);
        } else {
            // FEC 层先解码：数据分片立即交付，整组只缺 1 条时本地还原后再交付
            fec.decode(datagram, kcp::input);
        }
        // 立即 flush 回执 ACK，避免被 interval 节拍门控拖慢上行 RTT
        kcp.flush((int) System.currentTimeMillis());
        flushFec();
    }

    /** 取出 KCP 已组装好的一条可交付应用层消息；无则返回 null。 */
    public byte[] recvDelivered() {
        return kcp.recv();
    }

    /** 外部定时器驱动 KCP 演进（超时重传 / 窗口更新 / 节点间链路探活）。 */
    public void updateNow() {
        long now = System.currentTimeMillis();
        kcp.update(now);
        flushFec();
        if (fec != null) {
            fec.evictStale(now, FEC_GROUP_MAX_AGE_MS);
        }
        // 驱动后按剩余待处理状态决定下个节拍是否仍需加锁
        maintenancePending = kcp.hasPendingWork();
    }

    /** 是否仍有待处理状态（未确认在途/待发 ACK/探测），空闲会话返回 false。 */
    public boolean maintenancePending() {
        return maintenancePending;
    }

    /**
     * 向客户端发送一段原始应用层字节（走 KCP 可靠层）。
     * <p>加锁监视器即本会话实例：与 {@link KcpAuthHandler} 的 {@code synchronized (session)} 块使用同一
     * 监视器，从而把下行（ChannelActor 线程）与 KCP 输入/演进（业务线程池、后台定时线程）对底层
     * {@link Kcp} 实例的访问串行化——这是 {@link Kcp} 非线程安全的前提条件。</p>
     */
    public synchronized void sendBytes(byte[] data) {
        lastActiveMs = System.currentTimeMillis();
        maintenancePending = true;
        kcp.send(data);
        // 下行立即 flush，避免进入-响应时延被 interval 节拍放大
        kcp.flush((int) System.currentTimeMillis());
        flushFec();
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
        } catch (Exception e) {
            log.warn("Failed to notify KCP session close: channelId={}, remote={}", channelId, remote, e);
        }
    }

    public String channelId() {
        return channelId;
    }

    /** 本会话的 KCP 会话号，用于识别同一源地址上是否换成了新连接。 */
    public int conv() {
        return kcp.conv();
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
