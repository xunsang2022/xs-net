package com.game.logic.actor;

import com.game.logic.context.LogicContext;
import com.game.message.actor.logic.GMMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.management.GmKickPlayerRequest;
import com.xs.core.actor.RpcActor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Map;

@Slf4j
public final class GMActor extends RpcActor<GMMessage> {

    private GMActor(ActorContext<GMMessage> context, String entityId) {
        super(context, entityId);
    }

    public static Behavior<GMMessage> create(String entityId) {
        return Behaviors.setup(context -> new GMActor(context, entityId));
    }

    @Override
    public Receive<GMMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(GmKickPlayerRequest.class, this::onKickPlayer)
                .build();
    }

    private Behavior<GMMessage> onKickPlayer(GmKickPlayerRequest request) {
        if (!audit(request)) {
            return this;
        }
        sendRpc(
                PlayerMessage.class,
                new GmKickPlayerCommand(
                        request.requestId(),
                        request.operator(),
                        request.reason(),
                        request.issuedAt()),
                String.valueOf(request.playerId()));
        log.info("GM kick accepted: requestId={}, playerId={}, operator={}",
                request.requestId(), request.playerId(), request.operator());
        return this;
    }

    private boolean audit(GmKickPlayerRequest request) {
        try {
            LogicContext.getRedisStore().writeLog(Map.of(
                    "level", "INFO",
                    "playerId", String.valueOf(request.playerId()),
                    "content", "gm_kick requestId=" + request.requestId()
                            + " operator=" + request.operator()
                            + " reason=" + request.reason()));
            return true;
        } catch (Exception exception) {
            // Privileged actions are fail-closed when an audit record cannot be persisted.
            log.error("GM kick rejected because audit persistence failed: requestId={}, playerId={}",
                    request.requestId(), request.playerId(), exception);
            return false;
        }
    }
}
