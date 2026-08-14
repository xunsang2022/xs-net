package com.xs.net.message;

import com.baidu.bjf.remoting.protobuf.Codec;
import com.baidu.bjf.remoting.protobuf.ProtobufProxy;
import com.xs.net.message.annotation.ActionMessage;
import lombok.experimental.UtilityClass;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public class MessageAble {
    /// 协议解析map
    public static final ConcurrentHashMap<Integer, Class<?>> rpcMap = new ConcurrentHashMap<>();

    /// net byte -> net dto
    ///  ----------------消息协议格式---------------------
    ///   packetLength | rpcNum | errorCode | body
    ///      int          int       int       byte[]
    /// --------------------------------------------------
    public static NetMessage toNetMessage(byte[] bytes) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        int totalLength = bb.getInt();
        bb.getInt();
        bb.getInt();

        byte[] data = new byte[totalLength - 12];
        bb.get(data); // 修正：使用get方法填充data数组

        Codec<NetMessage> codec = ProtobufProxy.create(NetMessage.class);
        return codec.decode(data);
    }

    /// net dto -> action message dto
    public static <T> T toActionMessage(NetMessage netMessage) throws IOException {
        int rpcNum = netMessage.getRpcNum();
        Class<?> clazz = rpcMap.get(rpcNum);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown rpc number: " + rpcNum);
        }
        Codec<?> protobufProxy = ProtobufProxy.create(clazz);
        return (T) protobufProxy.decode(netMessage.getData());
    }

    /// action message dto -> net message dto
    public static <T> NetMessage toNetMessage(T message) throws IOException {
        Class<T> aClass = (Class<T>) message.getClass();
        Codec<T> codec = ProtobufProxy.create(aClass);
        byte[] data = codec.encode(message);
        return new NetMessage(aClass.getAnnotation(ActionMessage.class).value(), data);
    }

    ///  net message dto -> net byte
    ///  ----------------消息协议格式---------------------
    ///   packetLength | rpcNum | errorCode | body
    ///      int          int       int       byte[]
    /// --------------------------------------------------
    public static byte[] toNetByteMsg(NetMessage netMessage) throws IOException {
        Codec<NetMessage> codec = ProtobufProxy.create(NetMessage.class);
        int rpcNumber = netMessage.rpcNum;
        byte[] data = codec.encode(netMessage);

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
}