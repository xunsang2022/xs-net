package com.xs.net.message;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;

import java.util.Objects;

/**
 * RPC运行时依赖类，提供RPC消息的编码和解码功能。
 * 封装了消息注册表和信封工厂，作为消息处理的运行时上下文。
 */
public final class RpcRuntime {
    /** RPC消息注册表，用于管理所有RPC消息规范 */
    private final RpcMessageRegistry registry;
    /** RPC信封工厂，用于创建RPC消息信封 */
    private final RpcEnvelopeFactory envelopeFactory;

    /**
     * 构造RPC运行时对象，仅使用消息注册表。
     *
     * @param registry RPC消息注册表
     */
    public RpcRuntime(RpcMessageRegistry registry) {
        this(registry, null);
    }

    /**
     * 构造RPC运行时对象，使用消息注册表和信封工厂。
     *
     * @param registry RPC消息注册表
     * @param envelopeFactory RPC信封工厂
     */
    public RpcRuntime(RpcMessageRegistry registry, RpcEnvelopeFactory envelopeFactory) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.envelopeFactory = envelopeFactory;
    }

    /**
     * 获取RPC消息注册表。
     *
     * @return RPC消息注册表
     */
    public RpcMessageRegistry registry() {
        return registry;
    }

    /**
     * 解码网络消息为业务消息对象。
     *
     * @param envelope 网络消息信封
     * @return 业务消息对象
     * @throws InvalidProtocolBufferException 如果解析失败
     */
    public Object decode(NetMessage envelope) throws InvalidProtocolBufferException {
        return MessageAble.toActionMessage(envelope, registry, envelopeFactory);
    }

    /**
     * 解码网络消息为RPC传递对象，包含消息内容和请求上下文。
     *
     * @param envelope 网络消息信封
     * @return RPC传递对象
     * @throws InvalidProtocolBufferException 如果解析失败
     */
    public RpcDelivery<Object> decodeDelivery(NetMessage envelope) throws InvalidProtocolBufferException {
        return new RpcDelivery<>(decode(envelope), RequestContext.from(envelope));
    }

    /**
     * 将业务消息对象编码为网络消息。
     *
     * @param message 业务消息对象
     * @return 网络消息
     */
    public NetMessage encode(Object message) {
        return MessageAble.toNetMessage(message, registry);
    }

    /**
     * 将业务消息对象编码为网络消息，并应用请求上下文。
     *
     * @param message 业务消息对象
     * @param context 请求上下文
     * @return 网络消息
     */
    public NetMessage encode(Object message, RequestContext context) {
        NetMessage base = encode(message);
        return context.applyTo(base.toBuilder()).build();
    }

    /**
     * 将RPC消息对象编码为网络消息，并应用请求上下文。
     *
     * @param message RPC消息对象
     * @param context 请求上下文
     * @return 网络消息
     */
    public NetMessage encode(RpcMessage message, RequestContext context) {
        NetMessage base = MessageAble.toNetMessage(message, registry);
        return context.applyTo(base.toBuilder()).build();
    }

    /**
     * 根据RPC编号查找消息规范。
     *
     * @param rpcNum RPC编号
     * @return 消息规范，如果未找到返回null
     */
    public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
        return registry.find(rpcNum);
    }
}
