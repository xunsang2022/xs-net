package com.xs.net.message;

import java.lang.reflect.Method;
import java.util.Objects;

public record RpcHandlerMetadata(
        String actorType,
        String methodName,
        Class<?> requestType,
        Class<?> responseType
) {
    public RpcHandlerMetadata {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(requestType, "requestType");
    }

    public boolean hasResponse() {
        return responseType != null;
    }

    public Object invoke(Object actor, Object request) throws Exception {
        if (!actorType.equals(actor.getClass().getName())) {
            throw new IllegalArgumentException("RPC actor type mismatch: " + actor.getClass().getName());
        }
        if (!requestType.isInstance(request)) {
            throw new IllegalArgumentException("RPC request type mismatch: " + request.getClass().getName());
        }
        Method method = actor.getClass().getDeclaredMethod(methodName, requestType);
        method.setAccessible(true);
        return method.invoke(actor, request);
    }
}
