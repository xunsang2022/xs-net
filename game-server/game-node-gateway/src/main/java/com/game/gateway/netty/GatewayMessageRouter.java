package com.game.gateway.netty;

import com.game.message.actor.logic.PlayerMessage;
import com.game.message.c2s.LoginRequest;
import com.game.message.c2s.PlayerEnterCommand;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Routes a decoded client {@link NetMessage} into the existing PlayerActor path. */
@Slf4j
final class GatewayMessageRouter {

    private static final ConcurrentMap<Class<?>, Method> PLAYER_ID_METHODS = new ConcurrentHashMap<>();

    void route(byte[] body) throws Exception {
        route(NetMessage.parseFrom(body));
    }

    void route(NetMessage netMessage) throws Exception {
        RpcMessageSpec<? extends MessageLite> spec = GameRpcMessages.INSTANCE.find(netMessage.getRpcNum());
        if (spec == null) {
            log.warn("Unknown rpcNum: {}", netMessage.getRpcNum());
            return;
        }
        MessageLite payload = spec.parser().parseFrom(netMessage.getData());
        Object actionMessage = spec.decode(payload);
        String playerId = extractPlayerId(actionMessage.getClass(), actionMessage);
        if (playerId == null || playerId.isBlank()) {
            log.warn("Message without playerId, rpcNum={}", netMessage.getRpcNum());
            return;
        }
        if (actionMessage instanceof LoginRequest login) {
            ChannelRegistry.INSTANCE.recordLoginIngress(login.sessionId(), System.nanoTime());
            PlayerEnterCommand command = new PlayerEnterCommand(
                    Long.parseLong(login.playerId()), login.playerName(), login.sessionId());
            AkkaKit.tell(PlayerMessage.class, command, playerId);
            return;
        }
        AkkaKit.tell(PlayerMessage.class, (PlayerMessage) actionMessage, playerId);
    }

    static String extractPlayerId(Class<?> type, Object payload) {
        try {
            Method method = PLAYER_ID_METHODS.computeIfAbsent(type, candidate -> {
                try {
                    return candidate.getMethod("playerId");
                } catch (NoSuchMethodException ignored) {
                    return null;
                }
            });
            Object playerId = method == null ? null : method.invoke(payload);
            return playerId == null ? null : playerId.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
