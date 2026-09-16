package com.xs.core.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RequestContext;
import com.xs.net.message.RequestContextHolder;
import com.xs.net.message.RpcDelivery;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.RpcRuntime;
import com.xs.net.message.pb.NetMessage;
import io.micrometer.core.instrument.Metrics;

import java.util.Objects;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.pekko.japi.function.Function;

public abstract class RpcActor<M> extends AbstractBehavior<M> {

    private static final String METRIC_ENVELOPE_DROPPED = "xs.rpc.actor.envelope.dropped";

    private final String entityId;
    private final RpcMessageRegistry registry;
    private final RpcRuntime runtime;
    private final Map<Object, RequestContext> deliveryContexts =
            Collections.synchronizedMap(new IdentityHashMap<>());

    protected RpcActor(ActorContext<M> context, String entityId) {
        this(context, entityId, requireRegistry());
    }

    protected RpcActor(ActorContext<M> context, String entityId, RpcMessageRegistry registry) {
        super(context);
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runtime = new RpcRuntime(registry);
    }

    protected final String entityId() {
        return entityId;
    }

    protected final RpcMessageRegistry registry() {
        return registry;
    }

    protected final RequestContext currentRequestContext() {
        return RequestContextHolder.current();
    }

    protected final <T extends M> Function<T, Behavior<M>> contextual(Function<T, Behavior<M>> handler) {
        return message -> {
            RequestContext context = deliveryContexts.remove(message);
            return RequestContextHolder.call(context, () -> {
                try {
                    return handler.apply(message);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        };
    }

    protected final Behavior<M> contextual(M message, Function<M, Behavior<M>> handler) {
        RequestContext context = deliveryContexts.remove(message);
        return RequestContextHolder.call(context, () -> {
            try {
                return handler.apply(message);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    protected final void sendRpc(Class<?> roleClass, Object message, String targetEntityId) {
        RequestContext context = currentRequestContext();
        AkkaKit.tell(registry, roleClass, message, targetEntityId,
                context == null ? null : context.nextHop(null));
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
            RpcDelivery<Object> delivery = runtime.decodeDelivery(envelope);
            if (delivery.context().expired()) {
                recordDroppedEnvelope(rpcNum, spec.messageClass().getName(), "deadline_expired", null);
                return this;
            }
            deliveryContexts.put(delivery.message(), delivery.context());
            getContext().getSelf().tell((M) delivery.message());
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
