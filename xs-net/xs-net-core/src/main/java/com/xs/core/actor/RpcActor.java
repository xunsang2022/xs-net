package com.xs.core.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;

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

    /**
     * Adds support for protocol envelopes delivered to an entity hosted on this JVM.
     * Remote transport performs the same conversion through {@code NetMessageSerializer}.
     */
    @SuppressWarnings("unchecked")
    protected final ReceiveBuilder<M> newRpcReceiveBuilder() {
        return newReceiveBuilder().onMessage(
                (Class<M>) (Class<?>) NetMessage.class,
                message -> onNetMessage((NetMessage) message));
    }

    @SuppressWarnings("unchecked")
    private Behavior<M> onNetMessage(NetMessage envelope) {
        try {
            Object decoded = MessageAble.toActionMessage(envelope);
            getContext().getSelf().tell((M) decoded);
        } catch (Exception exception) {
            getContext().getLog().warn("Dropping invalid RPC envelope for entity {}", entityId, exception);
        }
        return this;
    }

    private static RpcMessageRegistry requireRegistry() {
        RpcMessageRegistry registry = MessageAble.registry();
        if (registry == null) {
            throw new IllegalStateException("RpcMessageRegistry has not been initialized");
        }
        return registry;
    }
}
