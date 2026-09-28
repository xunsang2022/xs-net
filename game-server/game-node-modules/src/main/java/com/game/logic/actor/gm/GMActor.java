package com.game.logic.actor.gm;

import com.game.logic.context.LogicContext;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.logic.GMMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.management.GmKickPlayerRequest;
import com.xs.kernel.actor.RpcActor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/** 处理 GM 管理请求并在审计成功后向玩家 Actor 分发指令的 Actor。 */
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
                .onMessage(GmKickPlayerRequest.class, contextual(this::onKickPlayer))
                .onMessage(AuditResult.class, this::onAuditResult)
                .build();
    }

    private Behavior<GMMessage> onKickPlayer(GmKickPlayerRequest request) {
        RedisStore store;
        try {
            store = LogicContext.getRedisStore();
        } catch (RuntimeException exception) {
            getContext().getSelf().tell(new AuditResult(request, false, exception));
            return this;
        }
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(() -> audit(store, request), LogicContext.getRedisIoExecutor());
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new AuditResult(request, false, exception));
            return this;
        }
        getContext().pipeToSelf(future, (success, error) -> new AuditResult(request, success, error));
        return this;
    }

    private Behavior<GMMessage> onAuditResult(AuditResult result) {
        if (result.error != null || !Boolean.TRUE.equals(result.success)) {
            log.error("GM kick rejected because audit persistence failed: requestId={}, playerId={}",
                    result.request.requestId(), result.request.playerId(), result.error);
            return this;
        }
        sendRpc(
                PlayerMessage.class,
                new GmKickPlayerCommand(
                        result.request.requestId(),
                        result.request.operator(),
                        result.request.reason(),
                        result.request.issuedAt()),
                String.valueOf(result.request.playerId()));
        log.info("GM kick accepted: requestId={}, playerId={}, operator={}",
                result.request.requestId(), result.request.playerId(), result.request.operator());
        return this;
    }

    private boolean audit(RedisStore store, GmKickPlayerRequest request) {
        try {
            store.writeLog(Map.of(
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
