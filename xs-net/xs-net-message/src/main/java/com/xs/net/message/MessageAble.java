package com.xs.net.message;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;

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
        int rpcNum = netMessage.getRpcNum();
        RpcMessageSpec<? extends MessageLite> spec = requireRegistry().find(rpcNum);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown rpc number: " + rpcNum);
        }
        MessageLite payload = spec.parser().parseFrom(netMessage.getData());
        if (envelopeFactory == null) {
            return spec.decode(payload);
        }
        return envelopeFactory.create(rpcNum, payload);
    }

    public static NetMessage toNetMessage(Object message) {
        if (message instanceof RpcMessage rpcMessage) {
            return toNetMessage(rpcMessage);
        }
        RpcMessageSpec<? extends MessageLite> spec = requireRegistry().findByDtoClass(message.getClass());
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

    private static RpcEnvelopeFactory requireFactory() {
        RpcEnvelopeFactory value = envelopeFactory;
        if (value == null) {
            throw new IllegalStateException("RpcEnvelopeFactory has not been initialized");
        }
        return value;
    }
}
