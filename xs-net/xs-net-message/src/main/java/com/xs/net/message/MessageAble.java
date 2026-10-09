package com.xs.net.message;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 消息能力工具类，提供网络消息与业务消息之间的转换功能。
 * 支持字节数组、NetMessage、ActionMessage之间的相互转换。
 */
public final class MessageAble {
    /** RPC消息注册表，用于管理所有RPC消息规范 */
    private static volatile RpcMessageRegistry registry;
    /** RPC信封工厂，用于创建RPC消息信封 */
    private static volatile RpcEnvelopeFactory envelopeFactory;

    /**
     * 初始化消息能力工具，设置RPC消息注册表和信封工厂。
     *
     * @param messageRegistry RPC消息注册表
     * @param messageEnvelopeFactory RPC信封工厂
     */
    public static void initialize(RpcMessageRegistry messageRegistry, RpcEnvelopeFactory messageEnvelopeFactory) {
        registry = messageRegistry;
        envelopeFactory = messageEnvelopeFactory;
    }

    /**
     * 初始化消息能力工具，仅设置RPC消息注册表。
     *
     * @param messageRegistry RPC消息注册表
     */
    public static void initialize(RpcMessageRegistry messageRegistry) {
        registry = messageRegistry;
        envelopeFactory = null;
    }

    /**
     * 检查消息能力工具是否已初始化。
     *
     * @return 如果已初始化返回true，否则返回false
     */
    public static boolean isInitialized() {
        return registry != null;
    }

    /**
     * 获取当前RPC消息注册表。
     *
     * @return RPC消息注册表
     */
    public static RpcMessageRegistry registry() {
        return registry;
    }

    /**
     * 将网络字节数组转换为NetMessage对象。
     * 消息协议格式：packetLength(4字节) | rpcNum(4字节) | errorCode(4字节) | body(变长)
     *
     * @param bytes 网络字节数组
     * @return NetMessage对象
     * @throws InvalidProtocolBufferException 如果字节数组长度不足或解析失败
     */
    public static NetMessage toNetMessage(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null || bytes.length < 12) {
            throw new InvalidProtocolBufferException("Frame too short: " + (bytes == null ? 0 : bytes.length));
        }
        return NetMessage.parseFrom(ByteBuffer.wrap(bytes, 12, bytes.length - 12));
    }

    /**
     * 将NetMessage对象转换为业务消息对象。
     *
     * @param netMessage 网络消息对象
     * @return 业务消息对象
     * @throws InvalidProtocolBufferException 如果解析失败
     */
    public static Object toActionMessage(NetMessage netMessage) throws InvalidProtocolBufferException {
        return toActionMessage(netMessage, requireRegistry(), envelopeFactory);
    }

    /**
     * 将NetMessage对象转换为业务消息对象，使用指定的注册表。
     *
     * @param netMessage 网络消息对象
     * @param messageRegistry RPC消息注册表
     * @return 业务消息对象
     * @throws InvalidProtocolBufferException 如果解析失败
     */
    public static Object toActionMessage(
            NetMessage netMessage,
            RpcMessageRegistry messageRegistry
    ) throws InvalidProtocolBufferException {
        return toActionMessage(netMessage, messageRegistry, null);
    }

    static Object toActionMessage(
            NetMessage netMessage,
            RpcMessageRegistry messageRegistry,
            RpcEnvelopeFactory messageEnvelopeFactory
    ) throws InvalidProtocolBufferException {
        Objects.requireNonNull(netMessage, "netMessage");
        RpcMessageRegistry registry = requireRegistry(messageRegistry);
        int rpcNum = netMessage.getRpcNum();
        RpcMessageSpec<? extends MessageLite> spec = registry.find(rpcNum);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown rpc number: " + rpcNum);
        }
        MessageLite payload = spec.parser().parseFrom(netMessage.getData());
        if (messageEnvelopeFactory == null) {
            return spec.decode(payload);
        }
        return messageEnvelopeFactory.create(rpcNum, payload);
    }

    /**
     * 将业务消息对象转换为NetMessage对象。
     *
     * @param message 业务消息对象
     * @return NetMessage对象
     * @throws IllegalArgumentException 如果消息类型未知
     */
    public static NetMessage toNetMessage(Object message) {
        return toNetMessage(message, requireRegistry());
    }

    /**
     * 将业务消息对象转换为NetMessage对象，使用指定的注册表。
     *
     * @param message 业务消息对象
     * @param messageRegistry RPC消息注册表
     * @return NetMessage对象
     * @throws IllegalArgumentException 如果消息类型未知
     */
    public static NetMessage toNetMessage(Object message, RpcMessageRegistry messageRegistry) {
        Objects.requireNonNull(message, "message");
        RpcMessageRegistry registry = requireRegistry(messageRegistry);
        if (message instanceof RpcMessage rpcMessage) {
            return toNetMessage(rpcMessage, registry);
        }
        RpcMessageSpec<? extends MessageLite> spec = registry.findByDtoClass(message.getClass());
        if (spec == null) {
            throw new IllegalArgumentException("Unknown RPC DTO: " + message.getClass().getName());
        }
        return NetMessage.newBuilder()
                .setRpcNum(spec.rpcNum())
                .setData(ByteString.copyFrom(spec.encode(message).toByteArray()))
                .build();
    }

    /**
     * 将RPC消息对象转换为NetMessage对象。
     *
     * @param message RPC消息对象
     * @return NetMessage对象
     * @throws IllegalArgumentException 如果消息类型未知或负载类型不匹配
     */
    public static NetMessage toNetMessage(RpcMessage message) {
        return toNetMessage(message, requireRegistry());
    }

    /**
     * 将RPC消息对象转换为NetMessage对象，使用指定的注册表。
     *
     * @param message RPC消息对象
     * @param messageRegistry RPC消息注册表
     * @return NetMessage对象
     * @throws IllegalArgumentException 如果消息类型未知或负载类型不匹配
     */
    public static NetMessage toNetMessage(RpcMessage message, RpcMessageRegistry messageRegistry) {
        Objects.requireNonNull(message, "message");
        RpcMessageRegistry registry = requireRegistry(messageRegistry);
        RpcMessageSpec<? extends MessageLite> spec = registry.find(message.rpcNum());
        if (spec == null) {
            throw new IllegalArgumentException("Unknown rpc number: " + message.rpcNum());
        }
        if (message.payload() == null || !spec.messageClass().isInstance(message.payload())) {
            throw new IllegalArgumentException("Unexpected payload type for rpc number " + message.rpcNum()
                    + ": " + (message.payload() == null ? "null" : message.payload().getClass().getName()));
        }
        byte[] data = message.payload().toByteArray();
        return NetMessage.newBuilder()
                .setRpcNum(message.rpcNum())
                .setData(ByteString.copyFrom(data))
                .build();
    }

    /**
     * 将NetMessage对象转换为网络字节数组。
     * 消息协议格式：packetLength(4字节) | rpcNum(4字节) | errorCode(4字节) | body(变长)
     *
     * @param netMessage 网络消息对象
     * @return 网络字节数组
     */
    public static byte[] toNetByteMsg(NetMessage netMessage) {
        int rpcNumber = netMessage.getRpcNum();
        byte[] data = netMessage.toByteArray();

        int errorCode = 0;
        int totalLength = data.length + 12;

        byte[] bytes = new byte[totalLength];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.putInt(totalLength);
        bb.putInt(rpcNumber);
        bb.putInt(errorCode);
        bb.put(data);

        return bytes;
    }

    /**
     * 将字节数组转换为NetMessage对象，支持带帧头或不带帧头的格式。
     *
     * @param bytes 字节数组
     * @param framed 是否带帧头（packetLength字段）
     * @return NetMessage对象
     * @throws InvalidProtocolBufferException 如果解析失败
     */
    public static NetMessage toNetMessage(byte[] bytes, boolean framed) throws InvalidProtocolBufferException {
        if (!framed) {
            return NetMessage.parseFrom(bytes);
        }
        return toNetMessage(bytes);
    }

    /**
     * 获取必需的RPC消息注册表，如果未初始化则抛出异常。
     *
     * @return RPC消息注册表
     * @throws IllegalStateException 如果注册表未初始化
     */
    private static RpcMessageRegistry requireRegistry() {
        RpcMessageRegistry value = registry;
        if (value == null) {
            throw new IllegalStateException("RpcMessageRegistry has not been initialized");
        }
        return value;
    }

    private static RpcMessageRegistry requireRegistry(RpcMessageRegistry value) {
        return Objects.requireNonNull(value, "messageRegistry");
    }

    private static RpcEnvelopeFactory requireFactory() {
        RpcEnvelopeFactory value = envelopeFactory;
        if (value == null) {
            throw new IllegalStateException("RpcEnvelopeFactory has not been initialized");
        }
        return value;
    }
}
