package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.xs.net.transport.PacketFrame;
import com.xs.net.transport.PacketId;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.channel.EventLoop;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /** 重复连接拒绝计数（TCP 特有；成功/失败/上行指标名见 {@link AuthSupport}）。 */
    private static final String METRIC_CONN_REJECTED = "gateway.connection.rejected";

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
            AuthSupport.Result result = AuthSupport.resolve(jwtUtil, packet.body());
            if (result.failure() == AuthSupport.Failure.INVALID_TOKEN) {
                log.warn("Channel auth failed: invalid token");
                AuthSupport.countFailure(AuthSupport.TCP_METRICS);
                failAuth(ctx);
                return;
            }
            if (result.failure() == AuthSupport.Failure.MISSING_CHANNEL_ID) {
                log.warn("Channel auth failed: token has no channelId");
                AuthSupport.countFailure(AuthSupport.TCP_METRICS);
                failAuth(ctx);
                return;
            }
            channelId = result.channelId();
            connectionRandom = packet.random();
            // 单账号/单通道并发连接上限：已存在活跃连接时拒绝新连接
            if (!AuthSupport.admitReconnect(channelId, connectionRandom, config.getMaxConnectionsPerAccount())) {
                log.warn("Reject duplicate active connection: channelId={}", channelId);
                Metrics.counter(METRIC_CONN_REJECTED).increment();
                failAuth(ctx);
                return;
            }
            registeredEndpoint = new TcpClientEndpoint(ctx.channel(), connectionRandom);
            ChannelRegistry.INSTANCE.register(channelId, registeredEndpoint);
            authenticated = true;
            cancelAuthTimeout();
            AuthSupport.markLoginIngress(channelId);
            ctx.writeAndFlush(NettyFramer.encodeAck(connectionRandom, 0));
            AuthSupport.countSuccess(AuthSupport.TCP_METRICS);
        } catch (Exception e) {
            log.error("Channel auth error", e);
            AuthSupport.countFailure(AuthSupport.TCP_METRICS);
            ctx.close();
        }
    }

    private void handleGameMessage(ChannelHandlerContext ctx, byte[] body) {
        try {
            AuthSupport.countC2S(AuthSupport.TCP_METRICS);
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
}
