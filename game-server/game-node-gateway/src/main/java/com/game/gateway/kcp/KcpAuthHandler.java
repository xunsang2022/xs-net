package com.game.gateway.kcp;

import com.game.comment.util.JwtUtil;
import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.GatewayMessageRouter;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    private static final String METRIC_AUTH_SUCCESS = "gateway.kcp.auth.success";
    private static final String METRIC_AUTH_FAILURE = "gateway.kcp.auth.failure";
    private static final String METRIC_C2S_MESSAGES = "gateway.kcp.c2s.messages";

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
        KcpSession session = sessions.get(sender);
        if (session == null) {
            // 首次到达：按源地址建立未认证会话
            session = new KcpSession(null, sender, channel, config);
            sessions.put(sender, session);
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
            Metrics.counter(METRIC_C2S_MESSAGES).increment();
            router.route(message, session.channelId());
        } catch (Exception e) {
            log.error("Failed to handle KCP game message", e);
        }
    }

    /** 未认证会话的首个应用层载荷当作 JWT 校验。 */
    private void authenticate(KcpSession session, byte[] tokenBytes) {
        try {
            String token = new String(tokenBytes, StandardCharsets.UTF_8);
            Claims claims = jwtUtil.validateToken(token);
            if (claims == null) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                removeSession(session);
                return;
            }
            String channelId = jwtUtil.getChannelId(claims);
            if (channelId == null || channelId.isBlank()) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                removeSession(session);
                return;
            }
            if (config.getMaxConnectionsPerAccount() > 0
                    && ChannelRegistry.INSTANCE.isActive(channelId)) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                removeSession(session);
                return;
            }
            session.authenticateAs(channelId);
            ChannelRegistry.INSTANCE.register(channelId, session);
            // 回空载荷（0 长度）作为 auth-ok
            session.sendBytes(new byte[0]);
            Metrics.counter(METRIC_AUTH_SUCCESS).increment();
        } catch (Exception e) {
            Metrics.counter(METRIC_AUTH_FAILURE).increment();
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
        for (Map.Entry<InetSocketAddress, KcpSession> entry : sessions.entrySet()) {
            KcpSession session = entry.getValue();
            // 与业务线程池中的 receive 保持串行，避免 KCP 状态机并发访问
            synchronized (session) {
                session.updateNow();
                long idle = now - session.lastActiveMs();
                long idleLimit = config.getSessionIdleTimeoutSeconds() * 1000L;
                if (idle > idleLimit) {
                    if (session.channelId() != null) {
                        ChannelRegistry.INSTANCE.remove(session.channelId());
                    }
                    sessions.remove(entry.getKey(), session);
                    continue;
                }
                if (!session.isAuthenticated()
                        && idle > config.getAuthTimeoutSeconds() * 1000L) {
                    sessions.remove(entry.getKey(), session);
                }
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }
}
