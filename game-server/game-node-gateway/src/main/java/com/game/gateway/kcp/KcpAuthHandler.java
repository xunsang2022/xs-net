package com.game.gateway.kcp;

import com.game.comment.util.JwtUtil;
import com.game.gateway.netty.AuthSupport;
import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.GatewayMessageRouter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * KCP 认证 + 上行消息处理。
 * <p>按源地址 {@link InetSocketAddress} 建立/查找 {@link KcpSession}：未认证会话先把 KCP 收到的首个应用层
 * 载荷当作 JWT 校验（{@link JwtUtil}），成功后通过 {@link com.game.gateway.netty.ChannelRegistry} 注册并回一个
 * 空载荷（0 长度）作为 auth-ok；已认证载荷按 NetMessage 字节交给
 * {@link com.game.gateway.netty.GatewayMessageRouter}。整体仿照 UdpAuthHandler 的
 * sessions ConcurrentHashMap + synchronized(session) 串行 + 指标计数（gateway.kcp.*）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class KcpAuthHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    /** KCP 握手验签器。 */
    @NonNull
    private final JwtUtil jwtUtil;
    /** KCP 窗口、超时及帧限制配置。 */
    @NonNull
    private final KcpConfig config;
    /** KCP 数据包的业务处理线程池。 */
    @NonNull
    private final ExecutorService businessExecutor;
    private final GatewayMessageRouter router = new GatewayMessageRouter();
    private final Map<InetSocketAddress, KcpSession> sessions = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        DatagramChannel channel = (DatagramChannel) ctx.channel();
        InetSocketAddress sender = packet.sender();
        byte[] bytes = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), bytes);
        // 拷贝完成后脱离 event loop，提交到业务线程池并行处理（对照组自研 UDP）
        businessExecutor.submit(() -> processDatagram(channel, sender, bytes));
    }

    private void processDatagram(DatagramChannel channel, InetSocketAddress sender, byte[] bytes) {
        boolean fecEnabled = config.getFecK() > 0;
        // FEC 开启时帧头会遮蔽 KCP 会话号，须先在原数据报上定位内部 KCP 帧（不复制载荷，见统一热路径）
        boolean carriesSegment = fecEnabled ? FecCodec.isDataShard(bytes) : bytes.length >= Kcp.OVERHEAD;
        KcpSession session = sessions.get(sender);
        if (!carriesSegment) {
            // FEC 关闭：长度不足 KCP 帧头的非法报 → 丢弃；FEC 开启：冗余分片不含会话号，
            // 只能投递给已存在的会话；尚无会话说明其数据分片未到，本组无从还原，直接丢弃
            if (!fecEnabled || session == null) {
                return;
            }
        } else {
            int conv = fecEnabled ? FecCodec.peekConv(bytes) : Kcp.peekConv(bytes);
            if (session != null && session.conv() != conv) {
                // 同一源地址换成了新连接（会话号不同）：旧会话的收发进度对新连接无意义，
                // 必须重建，否则新连接的 JWT 会因序号落后于旧会话进度而被丢弃。
                removeSession(session);
                session = null;
            }
            if (session == null) {
                // 首次到达：按源地址建立未认证会话
                session = new KcpSession(conv, null, sender, channel, config);
                sessions.put(sender, session);
            }
        }
        // 每个会话串行处理，保证 KCP 状态机有序；不同会话可并行
        synchronized (session) {
            session.receive(bytes);
            byte[] message;
            while ((message = session.recvDelivered()) != null) {
                deliver(session, message);
            }
        }
    }

    /** 分发一条已组装的应用层消息：未认证 → JWT 认证；已认证 → 游戏消息路由。 */
    private void deliver(KcpSession session, byte[] message) {
        if (!session.isAuthenticated()) {
            authenticate(session, message);
            return;
        }
        try {
            AuthSupport.countC2S(AuthSupport.KCP_METRICS);
            router.route(message, session.channelId());
        } catch (Exception e) {
            log.error("Failed to handle KCP game message", e);
        }
    }

    /** 未认证会话的首个应用层载荷当作 JWT 校验。 */
    private void authenticate(KcpSession session, byte[] tokenBytes) {
        try {
            AuthSupport.Result result = AuthSupport.resolve(jwtUtil, tokenBytes);
            if (!result.success()) {
                AuthSupport.countFailure(AuthSupport.KCP_METRICS);
                removeSession(session);
                return;
            }
            String channelId = result.channelId();
            // KCP 会话不可复用旧连接语义：同 channelId 已有活跃连接即拒绝（差异逻辑，与 TCP/UDP 的重连替换不同）
            if (config.getMaxConnectionsPerAccount() > 0
                    && ChannelRegistry.INSTANCE.isActive(channelId)) {
                AuthSupport.countFailure(AuthSupport.KCP_METRICS);
                removeSession(session);
                return;
            }
            session.authenticateAs(channelId);
            ChannelRegistry.INSTANCE.register(channelId, session);
            AuthSupport.markLoginIngress(channelId);
            // 回空载荷（0 长度）作为 auth-ok
            session.sendBytes(new byte[0]);
            AuthSupport.countSuccess(AuthSupport.KCP_METRICS);
        } catch (Exception e) {
            AuthSupport.countFailure(AuthSupport.KCP_METRICS);
            log.error("KCP auth error", e);
            removeSession(session);
        }
    }

    private void removeSession(KcpSession session) {
        if (session.channelId() != null) {
            ChannelRegistry.INSTANCE.remove(session.channelId());
        }
        sessions.remove(session.remote(), session);
    }

    /** 后台定时：驱动各会话 KCP 演进 + 清理超时空闲 / 认证超时会话。 */
    public void tickAndCleanup() {
        long now = System.currentTimeMillis();
        long idleLimit = config.getSessionIdleTimeoutSeconds() * 1000L;
        long authLimit = config.getAuthTimeoutSeconds() * 1000L;
        for (Map.Entry<InetSocketAddress, KcpSession> entry : sessions.entrySet()) {
            KcpSession session = entry.getValue();
            long idle = now - session.lastActiveMs();
            boolean expired = idle > idleLimit || (!session.isAuthenticated() && idle > authLimit);
            // 空闲且未过期的会话无需驱动协议：跳过加锁，避免高并发下每个节拍锁住全部会话
            // （该监视器同时被下行 S2C 回包使用，是全量扫描成为瓶颈的根源）
            if (!expired && !session.maintenancePending()) {
                continue;
            }
            // 与业务线程池中的 receive 保持串行，避免 KCP 状态机并发访问
            synchronized (session) {
                if (expired) {
                    removeSession(session);
                    continue;
                }
                session.updateNow();
            }
        }
    }
}
