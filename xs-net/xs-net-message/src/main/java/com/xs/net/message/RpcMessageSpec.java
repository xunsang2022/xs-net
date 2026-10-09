package com.xs.net.message;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.util.Objects;

/**
 * RPC消息规范记录类，定义单个RPC消息的完整元数据。
 * 包含RPC编号、消息类型、解析器、角色类型、方向、路由需求、编解码器和处理器等信息。
 *
 * @param <T> Protocol Buffers消息类型
 * @param rpcNum RPC编号
 * @param messageClass Protocol Buffers消息类
 * @param parser 消息解析器
 * @param dtoClass DTO类
 * @param roleClass 目标角色类
 * @param direction 消息方向
 * @param publicVisible 是否对外可见
 * @param playerRoutingRequired 是否需要玩家路由
 * @param channelRoutingRequired 是否需要频道路由
 * @param codec 消息编解码器
 * @param handler 处理器元数据
 */
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

    /**
     * 简化的构造方法，用于内部消息。
     *
     * @param rpcNum RPC编号
     * @param messageClass Protocol Buffers消息类
     * @param parser 消息解析器
     * @param roleClass 目标角色类
     */
    public RpcMessageSpec(int rpcNum, Class<T> messageClass, Parser<T> parser, Class<?> roleClass) {
        this(rpcNum, messageClass, parser, null, roleClass, RpcMessageDirection.INTERNAL,
                false, false, false, null, null);
    }

    /**
     * 检查消息是否可路由。
     *
     * @return 如果可路由返回true，否则返回false
     */
    public boolean isRoutable() {
        return roleClass != null;
    }

    /**
     * 检查是否配置了编解码器。
     *
     * @return 如果已配置返回true，否则返回false
     */
    public boolean hasCodec() {
        return codec != null;
    }

    /**
     * 解码Protocol Buffers消息为DTO对象。
     *
     * @param payload Protocol Buffers消息
     * @return DTO对象
     * @throws IllegalStateException 如果未配置编解码器
     * @throws IllegalArgumentException 如果负载类型不匹配
     */
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

    /**
     * 编码DTO对象为Protocol Buffers消息。
     *
     * @param dto DTO对象
     * @return Protocol Buffers消息
     * @throws IllegalStateException 如果未配置编解码器
     * @throws IllegalArgumentException 如果DTO类型不匹配
     */
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
