package com.xs.net.message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

public record RpcHandlerMetadata(
        String actorType,
        String componentField,
        String methodName,
        Class<?> requestType,
        Class<?> responseType
) {
    public RpcHandlerMetadata {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(requestType, "requestType");
    }

    /**
     * 创建由 Actor 自身处理的 RPC 元数据。
     *
     * @param actorType Actor 的完整类名
     * @param methodName Actor 内处理方法名
     * @param requestType 请求 DTO 类型
     * @param responseType 响应 DTO 类型；内部消息为 {@code null}
     */
    public RpcHandlerMetadata(
            String actorType,
            String methodName,
            Class<?> requestType,
            Class<?> responseType
    ) {
        this(actorType, null, methodName, requestType, responseType);
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
        Object target = componentField == null ? actor : component(actor);
        Method method = target.getClass().getDeclaredMethod(methodName, requestType);
        method.setAccessible(true);
        return method.invoke(target, request);
    }

    /** 从 Actor 的组件字段取得实际 RPC 处理对象。 */
    private Object component(Object actor) throws ReflectiveOperationException {
        Field field = actor.getClass().getDeclaredField(componentField);
        field.setAccessible(true);
        Object component = field.get(actor);
        if (component == null) {
            throw new IllegalStateException("RPC handler component is null: " + componentField);
        }
        return component;
    }
}
