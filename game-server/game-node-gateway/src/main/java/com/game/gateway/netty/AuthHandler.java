package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.rpc.GameRpcEnvelope;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;
import io.jsonwebtoken.Claims;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 连接认证 + 上行消息处理。
 * 握手协议：连接建立后第一个帧为 JWT token（UTF-8），认证通过后进入游戏消息模式。
 * 游戏消息帧为 NetMessage 信封（rpcNum + payload），解码后路由到 Logic 的 PlayerActor。
 */
@Slf4j
public class AuthHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final JwtUtil jwtUtil;
    private boolean authenticated;
    private String channelId;

    public AuthHandler(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!authenticated) {
            authenticate(ctx, msg);
            return;
        }
        handleGameMessage(ctx, msg);
    }

    private void authenticate(ChannelHandlerContext ctx, ByteBuf msg) {
        try {
            byte[] tokenBytes = new byte[msg.readableBytes()];
            msg.readBytes(tokenBytes);
            String token = new String(tokenBytes, StandardCharsets.UTF_8);
            Claims claims = jwtUtil.validateToken(token);
            if (claims == null) {
                log.warn("Channel auth failed: invalid token");
                ctx.close();
                return;
            }
            channelId = jwtUtil.getChannelId(claims);
            if (channelId == null || channelId.isBlank()) {
                log.warn("Channel auth failed: token has no channelId");
                ctx.close();
                return;
            }
            ChannelRegistry.INSTANCE.register(channelId, ctx.channel());
            authenticated = true;
            log.info("Channel authenticated: channelId={}", channelId);
        } catch (Exception e) {
            log.error("Channel auth error", e);
            ctx.close();
        }
    }

    private void handleGameMessage(ChannelHandlerContext ctx, ByteBuf msg) {
        try {
            byte[] body = new byte[msg.readableBytes()];
            msg.readBytes(body);
            NetMessage netMessage = NetMessage.parseFrom(body);
            RpcMessageSpec<? extends MessageLite> spec = GameRpcMessages.INSTANCE.find(netMessage.getRpcNum());
            if (spec == null) {
                log.warn("Unknown rpcNum: {}", netMessage.getRpcNum());
                return;
            }
            MessageLite payload = spec.parser().parseFrom(netMessage.getData());
            GameRpcEnvelope envelope = new GameRpcEnvelope(netMessage.getRpcNum(), payload);
            String playerId = extractPlayerId(payload);
            if (playerId == null || playerId.isBlank()) {
                log.warn("Message without playerId, rpcNum={}", netMessage.getRpcNum());
                return;
            }
            AkkaKit.tell(PlayerMessage.class, envelope, playerId);
        } catch (Exception e) {
            log.error("Failed to handle game message, closing channel: channelId={}", channelId, e);
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (channelId != null) {
            ChannelRegistry.INSTANCE.remove(channelId);
            log.info("Channel closed: channelId={}", channelId);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception: channelId={}", channelId, cause);
        ctx.close();
    }

    /**
     * 从 protobuf 消息中反射提取 playerId（兼容 string / int64 字段）。
     */
    private static String extractPlayerId(MessageLite payload) {
        try {
            Method method = payload.getClass().getMethod("getPlayerId");
            Object value = method.invoke(payload);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}