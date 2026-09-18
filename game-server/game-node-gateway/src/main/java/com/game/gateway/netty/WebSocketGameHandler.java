package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** WebSocket equivalent of the TCP authentication and game-message pipeline. */
@Slf4j
final class WebSocketGameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private static final String METRIC_AUTH_SUCCESS = "gateway.websocket.auth.success";
    private static final String METRIC_AUTH_FAILURE = "gateway.websocket.auth.failure";
    private static final String METRIC_CONN_REJECTED = "gateway.websocket.connection.rejected";
    private static final String METRIC_C2S_MESSAGES = "gateway.websocket.c2s.messages";

    private final JwtUtil jwtUtil;
    private final NettyConfig config;
    private final GatewayMessageRouter router = new GatewayMessageRouter();
    private boolean authenticated;
    private String channelId;
    private ScheduledFuture<?> authTimeoutTask;

    WebSocketGameHandler(JwtUtil jwtUtil, NettyConfig config) {
        this.jwtUtil = jwtUtil;
        this.config = config;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        if (config.getAuthTimeoutSeconds() > 0) {
            EventLoop eventLoop = ctx.channel().eventLoop();
            authTimeoutTask = eventLoop.schedule(() -> {
                if (!authenticated) {
                    log.warn("WebSocket auth timeout: remote={}", ctx.channel().remoteAddress());
                    ctx.close();
                }
            }, config.getAuthTimeoutSeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        ByteBuf content = frame.content();
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        if (!authenticated) {
            authenticate(ctx, bytes);
            return;
        }
        try {
            Metrics.counter(METRIC_C2S_MESSAGES).increment();
            router.route(bytes, channelId);
        } catch (Exception exception) {
            log.error("Failed to handle WebSocket game message, closing: channelId={}", channelId, exception);
            ctx.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelAuthTimeout();
        if (channelId != null) {
            ChannelRegistry.INSTANCE.remove(channelId);
        }
        ctx.fireChannelInactive();
    }

    private void authenticate(ChannelHandlerContext ctx, byte[] tokenBytes) {
        try {
            Claims claims = jwtUtil.validateToken(new String(tokenBytes, StandardCharsets.UTF_8));
            if (claims == null) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                ctx.close();
                return;
            }
            String resolvedChannelId = jwtUtil.getChannelId(claims);
            if (resolvedChannelId == null || resolvedChannelId.isBlank()) {
                Metrics.counter(METRIC_AUTH_FAILURE).increment();
                ctx.close();
                return;
            }
            if (config.getMaxConnectionsPerAccount() > 0 && ChannelRegistry.INSTANCE.isActive(resolvedChannelId)) {
                Metrics.counter(METRIC_CONN_REJECTED).increment();
                ctx.close();
                return;
            }
            channelId = resolvedChannelId;
            ChannelRegistry.INSTANCE.register(channelId, new WebSocketClientEndpoint(ctx.channel()));
            authenticated = true;
            cancelAuthTimeout();
            Metrics.counter(METRIC_AUTH_SUCCESS).increment();
            log.info("WebSocket channel authenticated: channelId={}", channelId);
        } catch (Exception exception) {
            Metrics.counter(METRIC_AUTH_FAILURE).increment();
            log.error("WebSocket authentication failed", exception);
            ctx.close();
        }
    }

    private void cancelAuthTimeout() {
        if (authTimeoutTask != null && !authTimeoutTask.isDone()) {
            authTimeoutTask.cancel(false);
        }
    }
}
