package com.xs.net.message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * RPC处理器元数据记录类，封装了RPC处理方法的必要信息。
 * 包含Actor类型、组件字段、方法名、请求和响应类型等信息，用于反射调用RPC处理方法。
 *
 * @param actorType Actor的完整类名
 * @param componentField 组件字段名，如果为null则表示由Actor自身处理
 * @param methodName 处理方法名
 * @param requestType 请求DTO类型
 * @param responseType 响应DTO类型，内部消息为null
 */
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

    /**
     * 检查是否有响应类型。
     *
     * @return 如果有响应类型返回true，否则返回false
     */
    public boolean hasResponse() {
        return responseType != null;
    }

    /**
     * 调用RPC处理方法。
     *
     * @param actor Actor实例
     * @param request 请求对象
     * @return 处理方法返回值
     * @throws Exception 如果调用失败
     */
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
