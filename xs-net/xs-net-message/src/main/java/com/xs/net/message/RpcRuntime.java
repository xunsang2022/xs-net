package com.xs.net.message;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;

import java.util.Objects;

/** Explicit runtime dependencies for RPC encoding and decoding. */
public final class RpcRuntime {
    private final RpcMessageRegistry registry;
    private final RpcEnvelopeFactory envelopeFactory;

    public RpcRuntime(RpcMessageRegistry registry) {
        this(registry, null);
    }

    public RpcRuntime(RpcMessageRegistry registry, RpcEnvelopeFactory envelopeFactory) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.envelopeFactory = envelopeFactory;
    }

    public RpcMessageRegistry registry() {
        return registry;
    }

    public Object decode(NetMessage envelope) throws InvalidProtocolBufferException {
        return MessageAble.toActionMessage(envelope, registry, envelopeFactory);
    }

    public RpcDelivery<Object> decodeDelivery(NetMessage envelope) throws InvalidProtocolBufferException {
        return new RpcDelivery<>(decode(envelope), RequestContext.from(envelope));
    }

    public NetMessage encode(Object message) {
        return MessageAble.toNetMessage(message, registry);
    }

    public NetMessage encode(Object message, RequestContext context) {
        NetMessage base = encode(message);
        return context.applyTo(base.toBuilder()).build();
    }

    public NetMessage encode(RpcMessage message, RequestContext context) {
        NetMessage base = MessageAble.toNetMessage(message, registry);
        return context.applyTo(base.toBuilder()).build();
    }

    public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
        return registry.find(rpcNum);
    }
}
