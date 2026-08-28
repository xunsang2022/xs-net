package com.xs.core.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;

public abstract class RpcActor<M> extends AbstractBehavior<M> {

    private final String entityId;
    private final RpcMessageRegistry registry;

    protected RpcActor(ActorContext<M> context, String entityId) {
        this(context, entityId, requireRegistry());
    }

    protected RpcActor(ActorContext<M> context, String entityId, RpcMessageRegistry registry) {
        super(context);
        this.entityId = entityId;
        this.registry = registry;
    }

    protected final String entityId() {
        return entityId;
    }

    protected final RpcMessageRegistry registry() {
        return registry;
    }

    protected final void sendRpc(Class<?> roleClass, Object message, String targetEntityId) {
        AkkaKit.tell(roleClass, message, targetEntityId);
    }

    private static RpcMessageRegistry requireRegistry() {
        RpcMessageRegistry registry = MessageAble.registry();
        if (registry == null) {
            throw new IllegalStateException("RpcMessageRegistry has not been initialized");
        }
        return registry;
    }
}
