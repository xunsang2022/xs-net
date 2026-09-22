package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.xs.net.transport.PacketFrame;
import com.xs.net.transport.PacketId;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.channel.EventLoop;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 连接认证 + 上行消息处理。
 * <p>挂载在业务线程池执行（见 {@link NettyChannelInitializer}），避免阻塞 I/O 事件循环。</p>
 * 握手协议：连接建立后第一个帧为 JWT token（UTF-8），认证通过后进入游戏消息模式。
 * 游戏消息帧为 NetMessage 信封（rpcNum + payload），解码后路由到 Logic 的 PlayerActor。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final String METRIC_AUTH_SUCCESS = "gateway.auth.success";
    private static final String METRIC_AUTH_FAILURE = "gateway.auth.failure";
    private static final String METRIC_CONN_REJECTED = "gateway.connection.rejected";
    private static final String METRIC_C2S_MESSAGES = "gateway.c2s.messages";

    /** JWT 验签组件。 */
    @NonNull
    private final JwtUtil jwtUtil;
    /** 认证超时、帧限制和背压配置。 */
    @NonNull
    private final NettyConfig config;
    private final GatewayMessageRouter router = new GatewayMessageRouter();

    private boolean authenticated;
    private String channelId;
    private ClientEndpoint registeredEndpoint;
    private Date tokenIssuedAt;
    private long connectionRandom;
    private ScheduledFuture<?> authTimeoutTask;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        // 握手认证超时保护：超时仍未提交有效 token 则踢出，防资源占用
        if (config.getAuthTimeoutSeconds() > 0) {
            EventLoop eventLoop = ctx.channel().eventLoop();
            authTimeoutTask = eventLoop.schedule(() -> {
                if (!authenticated) {
                    log.warn("Auth timeout, closing channel: local={}", ctx.channel().localAddress());
                    ctx.close();
                }
            }, config.getAuthTimeoutSeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        PacketFrame packet;
        try {
            packet = NettyFramer.decode(msg);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid XS packet, closing channel", exception);
            ctx.close();
            return;
        }
        if (!authenticated) {
            if (packet.packetId() != PacketId.HELLO) {
                failAuth(ctx);
                return;
            }
            authenticate(ctx, packet);
            return;
        }
        if (packet.random() != connectionRandom) {
            ctx.close();
            return;
        }
        if (ChannelRegistry.INSTANCE.isTokenRevoked(channelId, tokenIssuedAt)) {
            ctx.close();
            return;
        }
        if (packet.packetId() == PacketId.HEARTBEAT) {
            ctx.writeAndFlush(NettyFramer.encodeAck(connectionRandom, packet.sendSeq()));
            return;
        }
        if (packet.packetId() == PacketId.DATA) {
            ctx.writeAndFlush(NettyFramer.encodeAck(connectionRandom, packet.sendSeq()));
            handleGameMessage(ctx, packet.body());
        }
    }

    private void authenticate(ChannelHandlerContext ctx, PacketFrame packet) {
        try {
            byte[] tokenBytes = packet.body();
            String token = new String(tokenBytes, StandardCharsets.UTF_8);
            Claims claims = jwtUtil.validateToken(token);
            if (claims == null) {
                log.warn("Channel auth failed: invalid token");
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                failAuth(ctx);
                return;
            }
            channelId = jwtUtil.getChannelId(claims);
            if (channelId == null || channelId.isBlank()) {
                log.warn("Channel auth failed: token has no channelId");
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                failAuth(ctx);
                return;
            }
            if (ChannelRegistry.INSTANCE.isTokenRevoked(channelId, claims.getIssuedAt())) {
                log.warn("Channel auth failed: token was revoked, channelId={}", channelId);
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                failAuth(ctx);
                return;
            }
            tokenIssuedAt = claims.getIssuedAt();
            // 单账号/单通道并发连接上限：已存在活跃连接时拒绝新连接
            connectionRandom = packet.random();
            ClientEndpoint existing = ChannelRegistry.INSTANCE.get(channelId);
            if (config.getMaxConnectionsPerAccount() > 0 && existing != null && existing.isActive()) {
                if (existing.connectionRandom() != connectionRandom) {
                    log.warn("Reject duplicate active connection: channelId={}", channelId);
                    Metrics.counter(METRIC_CONN_REJECTED).increment();
                    failAuth(ctx);
                    return;
                }
                ChannelRegistry.INSTANCE.remove(channelId, existing);
                existing.close();
            }
            registeredEndpoint = new TcpClientEndpoint(ctx.channel(), connectionRandom);
            ChannelRegistry.INSTANCE.register(channelId, registeredEndpoint);
            authenticated = true;
            cancelAuthTimeout();
            ctx.writeAndFlush(NettyFramer.encodeAck(connectionRandom, 0));
            Metrics.counter(METRIC_AUTH_SUCCESS).increment();
            log.info("Channel authenticated: channelId={}", channelId);
        } catch (Exception e) {
            log.error("Channel auth error", e);
            Metrics.counter(METRIC_AUTH_FAILURE).increment();
            ctx.close();
        }
    }

    private void handleGameMessage(ChannelHandlerContext ctx, byte[] body) {
        try {
            Metrics.counter(METRIC_C2S_MESSAGES).increment();
            router.route(body, channelId);
        } catch (Exception e) {
            log.error("Failed to handle game message, closing channel: channelId={}", channelId, e);
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 读空闲超时：判定客户端已半开/失联，主动关闭并清理注册表
        if (evt instanceof IdleStateEvent) {
            log.warn("Channel idle timeout, closing: channelId={}", channelId);
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelAuthTimeout();
        if (channelId != null && registeredEndpoint != null) {
            ChannelRegistry.INSTANCE.remove(channelId, registeredEndpoint);
            log.info("Channel closed: channelId={}", channelId);
        }
        ctx.fireChannelInactive();
    }

    private void failAuth(ChannelHandlerContext ctx) {
        ctx.close();
    }

    private void cancelAuthTimeout() {
        if (authTimeoutTask != null && !authTimeoutTask.isDone()) {
            authTimeoutTask.cancel(false);
        }
    }

    /**
     * 从 protobuf 消息中反射提取 playerId（兼容 string / int64 字段）。
     * <p>Method 采用缓存，避免每条消息都执行 {@code getClass().getMethod()}。</p>
     */
    static String extractPlayerId(Class<?> type, Object payload) {
        return GatewayMessageRouter.extractPlayerId(type, payload);
    }
}
