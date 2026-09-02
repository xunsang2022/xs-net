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
import io.micrometer.core.instrument.Metrics;

import java.util.Objects;

public abstract class RpcActor<M> extends AbstractBehavior<M> {

    private static final String METRIC_ENVELOPE_DROPPED = "xs.rpc.actor.envelope.dropped";

    private final String entityId;
    private final RpcMessageRegistry registry;

    protected RpcActor(ActorContext<M> context, String entityId) {
        this(context, entityId, requireRegistry());
    }

    protected RpcActor(ActorContext<M> context, String entityId, RpcMessageRegistry registry) {
        super(context);
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    protected final String entityId() {
        return entityId;
    }

    protected final RpcMessageRegistry registry() {
        return registry;
    }

    protected final void sendRpc(Class<?> roleClass, Object message, String targetEntityId) {
        AkkaKit.tell(registry, roleClass, message, targetEntityId);
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
        int rpcNum = envelope.getRpcNum();
        RpcMessageSpec<?> spec = registry.find(rpcNum);
        if (spec == null) {
            recordDroppedEnvelope(rpcNum, "unknown", "unknown_rpc", null);
            return this;
        }
        try {
            Object decoded = MessageAble.toActionMessage(envelope, registry);
            getContext().getSelf().tell((M) decoded);
        } catch (Exception exception) {
            recordDroppedEnvelope(rpcNum, spec.messageClass().getName(), "decode_failure", exception);
        }
        return this;
    }

    private void recordDroppedEnvelope(
            int rpcNum,
            String messageType,
            String reason,
            Exception exception
    ) {
        Metrics.counter(METRIC_ENVELOPE_DROPPED, "reason", reason).increment();
        getContext().getLog().warn(
                "Dropping RPC envelope: entityId={}, rpcNum={}, messageType={}, reason={}, "
                        + "errorType={}, errorMessage={}",
                entityId,
                rpcNum,
                messageType,
                reason,
                exception == null ? "none" : exception.getClass().getName(),
                exception == null ? "none" : exception.getMessage());
        if (exception != null && getContext().getLog().isDebugEnabled()) {
            getContext().getLog().debug("RPC envelope decode failure", exception);
        }
    }

    private static RpcMessageRegistry requireRegistry() {
        RpcMessageRegistry registry = MessageAble.registry();
        if (registry == null) {
            throw new IllegalStateException("RpcMessageRegistry has not been initialized");
        }
        return registry;
    }
}
