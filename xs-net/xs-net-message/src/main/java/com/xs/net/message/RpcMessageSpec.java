package com.xs.net.message;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.util.Objects;

public record RpcMessageSpec<T extends MessageLite>(
        int rpcNum,
        Class<T> messageClass,
        Parser<T> parser,
        Class<?> dtoClass,
        Class<?> roleClass,
        RpcMessageDirection direction,
        boolean publicVisible,
        boolean playerRoutingRequired,
        boolean channelRoutingRequired,
        RpcPayloadCodec<?, T> codec,
        RpcHandlerMetadata handler
) {
    public RpcMessageSpec {
        if (rpcNum < 0) {
            throw new IllegalArgumentException("rpcNum must not be negative");
        }
        Objects.requireNonNull(messageClass, "messageClass");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(direction, "direction");
        if (codec != null && dtoClass == null) {
            throw new IllegalArgumentException("dtoClass is required when codec is configured");
        }
    }

    public RpcMessageSpec(int rpcNum, Class<T> messageClass, Parser<T> parser, Class<?> roleClass) {
        this(rpcNum, messageClass, parser, null, roleClass, RpcMessageDirection.INTERNAL,
                false, false, false, null, null);
    }

    public boolean isRoutable() {
        return roleClass != null;
    }

    public boolean hasCodec() {
        return codec != null;
    }

    @SuppressWarnings("unchecked")
    public Object decode(MessageLite payload) {
        if (codec == null) {
            throw new IllegalStateException("No codec configured for rpc number: " + rpcNum);
        }
        if (!messageClass.isInstance(payload)) {
            throw new IllegalArgumentException("Unexpected payload type for rpc number " + rpcNum + ": "
                    + payload.getClass().getName());
        }
        return codec.decode((T) payload);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public T encode(Object dto) {
        if (codec == null) {
            throw new IllegalStateException("No codec configured for rpc number: " + rpcNum);
        }
        if (!dtoClass.isInstance(dto)) {
            throw new IllegalArgumentException("Unexpected dto type for rpc number " + rpcNum + ": "
                    + dto.getClass().getName());
        }
        return (T) ((RpcPayloadCodec) codec).encode(dto);
    }
}
