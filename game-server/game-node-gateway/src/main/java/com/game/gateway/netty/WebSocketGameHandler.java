package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.xs.net.transport.PacketFrame;
import com.xs.net.transport.PacketId;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
    private ClientEndpoint registeredEndpoint;
    private long connectionRandom;
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
            PacketFrame packet;
            try {
                packet = NettyFramer.decode(Unpooled.wrappedBuffer(bytes));
            } catch (IllegalArgumentException exception) {
                ctx.close();
                return;
            }
            if (packet.packetId() != PacketId.HELLO) {
                ctx.close();
                return;
            }
            authenticate(ctx, packet);
            return;
        }
        try {
            PacketFrame packet = NettyFramer.decode(Unpooled.wrappedBuffer(bytes));
            if (packet.random() != connectionRandom) {
                ctx.close();
                return;
            }
            if (packet.packetId() == PacketId.HEARTBEAT || packet.packetId() == PacketId.DATA) {
                ctx.writeAndFlush(new BinaryWebSocketFrame(NettyFramer.encodeAck(connectionRandom, packet.sendSeq())));
            }
            if (packet.packetId() != PacketId.DATA) {
                return;
            }
            Metrics.counter(METRIC_C2S_MESSAGES).increment();
            router.route(packet.body(), channelId);
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
        if (channelId != null && registeredEndpoint != null) {
            ChannelRegistry.INSTANCE.remove(channelId, registeredEndpoint);
        }
        ctx.fireChannelInactive();
    }

    private void authenticate(ChannelHandlerContext ctx, PacketFrame packet) {
        try {
            Claims claims = jwtUtil.validateToken(new String(packet.body(), StandardCharsets.UTF_8));
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
            connectionRandom = packet.random();
            ClientEndpoint existing = ChannelRegistry.INSTANCE.get(resolvedChannelId);
            if (config.getMaxConnectionsPerAccount() > 0 && existing != null && existing.isActive()) {
                if (existing.connectionRandom() != connectionRandom) {
                    Metrics.counter(METRIC_CONN_REJECTED).increment();
                    ctx.close();
                    return;
                }
                ChannelRegistry.INSTANCE.remove(resolvedChannelId, existing);
                existing.close();
            }
            channelId = resolvedChannelId;
            registeredEndpoint = new WebSocketClientEndpoint(ctx.channel(), connectionRandom);
            ChannelRegistry.INSTANCE.register(channelId, registeredEndpoint);
            authenticated = true;
            cancelAuthTimeout();
            ctx.writeAndFlush(new BinaryWebSocketFrame(NettyFramer.encodeAck(connectionRandom, 0)));
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
