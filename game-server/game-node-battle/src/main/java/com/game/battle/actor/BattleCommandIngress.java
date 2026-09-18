package com.game.battle.actor;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.battle.BattleCreateCommand;
import com.google.protobuf.InvalidProtocolBufferException;
import com.xs.core.akka.RpcDeliveryInterceptor;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RequestContext;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.pb.NetMessage;

/**
 * 战斗消息快速入口。
 *
 * <p>本入口只负责把消息转交给专用战斗执行器；战斗状态、Tick 和高频输入不会在
 * Pekko Actor mailbox 中排队执行，从而避免单场战斗拖垮 Actor 邮箱。</p>
 */
public final class BattleCommandIngress implements RpcDeliveryInterceptor {
    /** 战斗服唯一持有的执行器池，负责按 battleId 串行化状态修改。 */
    private final BattleExecutorPool pool;

    public BattleCommandIngress(BattleExecutorPool pool) {
        this.pool = java.util.Objects.requireNonNull(pool, "pool");
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
