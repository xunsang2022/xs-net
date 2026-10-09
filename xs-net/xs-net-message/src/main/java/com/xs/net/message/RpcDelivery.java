package com.xs.net.message;

import java.util.Objects;

/**
 * RPC消息传递对象，封装了解码后的消息负载和相关的请求上下文。
 * 用于在Gateway和Actor之间传递完整的请求信息。
 *
 * @param <T> 消息类型
 * @param message 解码后的消息对象
 * @param context 请求上下文
 */
public record RpcDelivery<T>(T message, RequestContext context) {
    public RpcDelivery {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
