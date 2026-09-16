package com.xs.net.message;

import java.util.Objects;

/** A decoded RPC payload together with the metadata that travelled with it. */
public record RpcDelivery<T>(T message, RequestContext context) {
    public RpcDelivery {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
