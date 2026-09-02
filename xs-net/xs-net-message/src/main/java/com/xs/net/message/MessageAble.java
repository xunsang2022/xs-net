package com.xs.net.message;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.util.Objects;

@UtilityClass
public class MessageAble {
    private static volatile RpcMessageRegistry registry;
    private static volatile RpcEnvelopeFactory envelopeFactory;

    public static void initialize(RpcMessageRegistry messageRegistry, RpcEnvelopeFactory messageEnvelopeFactory) {
        registry = messageRegistry;
        envelopeFactory = messageEnvelopeFactory;
    }

    public static void initialize(RpcMessageRegistry messageRegistry) {
        registry = messageRegistry;
        envelopeFactory = null;
    }

    public static boolean isInitialized() {
        return registry != null;
    }

    public static RpcMessageRegistry registry() {
        return registry;
    }

    /// net byte -> net dto
    ///  ----------------消息协议格式---------------------
    ///   packetLength | rpcNum | errorCode | body
    ///      int          int       int       byte[]
    /// --------------------------------------------------
    public static NetMessage toNetMessage(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null || bytes.length < 12) {
            throw new InvalidProtocolBufferException("Frame too short: " + (bytes == null ? 0 : bytes.length));
        }
        return NetMessage.parseFrom(ByteBuffer.wrap(bytes, 12, bytes.length - 12));
    }

    /// net dto -> action message dto
    public static Object toActionMessage(NetMessage netMessage) throws InvalidProtocolBufferException {
        return toActionMessage(netMessage, requireRegistry(), envelopeFactory);
    }

    /// net dto -> action message dto, using the supplied registry
    public static Object toActionMessage(
            NetMessage netMessage,
            RpcMessageRegistry messageRegistry
    ) throws InvalidProtocolBufferException {
        return toActionMessage(netMessage, messageRegistry, null);
    }

    private static Object toActionMessage(
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

    public static NetMessage toNetMessage(Object message) {
        return toNetMessage(message, requireRegistry());
    }

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

    /// action message dto -> net message dto
    public static NetMessage toNetMessage(RpcMessage message) {
        return toNetMessage(message, requireRegistry());
    }

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

    ///  net message dto -> net byte
    ///  ----------------消息协议格式---------------------
    ///   packetLength | rpcNum | errorCode | body
    ///      int          int       int       byte[]
    /// --------------------------------------------------
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
