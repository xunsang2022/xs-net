package com.xs.net.message;

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
}
