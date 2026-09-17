package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.UdpConfig;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * UDP 认证 + 上行消息处理。
 * <p>未认证包（HELLO）先做 JWT 认证并建立 {@link UdpSession}；已认证会话的数据包交给
 * {@link UdpSession#receive} 走可靠层，DATA 消息解码后路由到 Logic 的 PlayerActor。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class UdpAuthHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final String METRIC_AUTH_SUCCESS = "gateway.udp.auth.success";
    private static final String METRIC_AUTH_FAILURE = "gateway.udp.auth.failure";
    private static final String METRIC_C2S_MESSAGES = "gateway.udp.c2s.messages";

    /** UDP 握手验签器。 */
    @NonNull
    private final JwtUtil jwtUtil;
    /** UDP 会话、帧长度及线程池配置。 */
    @NonNull
    private final UdpConfig config;
    /** 脱离 Netty EventLoop 后处理验签和消息路由的业务线程池。 */
    @NonNull
    private final ExecutorService businessExecutor;
    private final ConcurrentMap<InetSocketAddress, UdpSession> sessions = new ConcurrentHashMap<>();
    private final GatewayMessageRouter router = new GatewayMessageRouter();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        DatagramChannel channel = (DatagramChannel) ctx.channel();
        InetSocketAddress sender = packet.sender();
        byte[] bytes = new byte[packet.content().readableBytes()];
        packet.content().getBytes(packet.content().readerIndex(), bytes);
        // 完成字节拷贝后立即脱离 event loop，提交到业务线程池并行处理。
        // 单个 NioDatagramChannel 只绑定一个 event loop，若在 loop 内串行处理全部包，
        // 高并发握手（HELLO + JWT 校验 + 回 ACK）会成为吞吐瓶颈。
        businessExecutor.submit(() -> processDatagram(channel, sender, bytes));
    }

    private void processDatagram(DatagramChannel channel, InetSocketAddress sender, byte[] bytes) {
        UdpReliableLayer.Packet p = UdpReliableLayer.decode(Unpooled.wrappedBuffer(bytes));
        if (p == null) {
            return;
        }
        if (p.type() == UdpReliableLayer.TYPE_HELLO) {
            authenticate(channel, sender, p.payload());
            return;
        }
        UdpSession session = sessions.get(sender);
        if (session != null) {
            // 每个会话串行处理，保证可靠层有序；不同会话可并行
            synchronized (session) {
                session.receive(bytes);
            }
        }
    }

    private void authenticate(DatagramChannel channel, InetSocketAddress sender, byte[] tokenBytes) {
        try {
            String token = new String(tokenBytes, StandardCharsets.UTF_8);
            Claims claims = jwtUtil.validateToken(token);
            if (claims == null) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                return;
            }
            String channelId = jwtUtil.getChannelId(claims);
            if (channelId == null || channelId.isBlank()) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                return;
            }
            if (config.getMaxConnectionsPerAccount() > 0
                    && ChannelRegistry.INSTANCE.isActive(channelId)) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                return;
            }
            UdpSession session = new UdpSession(channelId, sender, channel, config, this::handleGameMessage);
            sessions.put(sender, session);
            ChannelRegistry.INSTANCE.register(channelId, session);
            // 回 ACK 确认握手完成
            channel.writeAndFlush(new DatagramPacket(
                    UdpReliableLayer.encode(UdpReliableLayer.TYPE_ACK, 0, 0, null), sender));
            Metrics.counter(METRIC_AUTH_SUCCESS).increment();
            log.debug("UDP session authenticated: channelId={}, remote={}", channelId, sender);
        } catch (Exception e) {
            Metrics.counter(METRIC_AUTH_FAILURE).increment();
            log.error("UDP auth error", e);
        }
    }

    /** 可靠层交付的上行 DATA 消息（NetMessage 字节）→ 解码并路由 Logic。 */
    private void handleGameMessage(byte[] body) {
        try {
            Metrics.counter(METRIC_C2S_MESSAGES).increment();
            router.route(body);
        } catch (Exception e) {
            log.error("Failed to handle UDP game message", e);
        }
    }

    /** 后台定时：重传未确认包 + 清理超时空闲会话。 */
    public void retryAndCleanup() {
        long now = System.currentTimeMillis();
        for (var entry : sessions.entrySet()) {
            UdpSession session = entry.getValue();
            // 与业务线程池中的 receive 保持串行，避免可靠层并发访问
            synchronized (session) {
                session.retryOutstanding();
                if (now - session.lastActiveMs() > config.getSessionIdleTimeoutSeconds() * 1000L) {
                    ChannelRegistry.INSTANCE.remove(session.channelId());
                    sessions.remove(entry.getKey(), session);
                    log.info("UDP session idle timeout, removed: channelId={}, remote={}",
                            session.channelId(), entry.getKey());
                }
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }
}
