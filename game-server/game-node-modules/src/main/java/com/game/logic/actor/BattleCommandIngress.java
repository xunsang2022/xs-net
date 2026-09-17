package com.game.logic.actor;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.battle.BattleCreateCommand;
import com.google.protobuf.InvalidProtocolBufferException;
import com.xs.core.akka.RpcDeliveryInterceptor;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RequestContext;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.pb.NetMessage;

import java.util.Objects;

/** Fast role-specific delivery path for locally active battles. */
public final class BattleCommandIngress implements RpcDeliveryInterceptor {
    private final BattleExecutorPool pool;

    public BattleCommandIngress(BattleExecutorPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    public BattleExecutorPool pool() {
        return pool;
    }

    public BattleRuntime activate(String battleId) {
        return pool.activate(battleId);
    }

    public BattleSubmissionResult submit(String battleId, Object command, RequestContext context) {
        return pool.submit(battleId, command, context);
    }

    @Override
    public boolean intercept(
            RpcMessageRegistry registry,
            Class<?> roleClass,
            Object message,
            String entityId,
            RequestContext context
    ) {
        Object command = decode(registry, message);
        if (!(command instanceof BattleMessage)) {
            return false;
        }
        RequestContext effectiveContext = context;
        if (effectiveContext == null && message instanceof NetMessage netMessage) {
            effectiveContext = RequestContext.from(netMessage);
        }
        BattleRuntime runtime = pool.find(entityId);
        if (runtime == null) {
            return false;
        }
        BattleSubmissionResult result = pool.submit(entityId, command, effectiveContext);
        return result != BattleSubmissionResult.UNKNOWN_BATTLE;
    }

    private Object decode(RpcMessageRegistry registry, Object message) {
        if (message instanceof NetMessage netMessage) {
            try {
                return MessageAble.toActionMessage(netMessage, registry);
            } catch (InvalidProtocolBufferException | RuntimeException exception) {
                return null;
            }
        }
        return message;
    }
}
