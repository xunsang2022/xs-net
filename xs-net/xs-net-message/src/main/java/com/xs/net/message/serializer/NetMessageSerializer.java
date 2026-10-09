package com.xs.net.message.serializer;

import org.apache.pekko.serialization.Serializer;
import com.xs.net.message.pb.NetMessage;
import scala.Option;

/**
 * 网络消息序列化器，实现Pekko序列化接口。
 * 负责NetMessage对象与字节数组之间的序列化和反序列化。
 */
public class NetMessageSerializer implements Serializer {

    /**
     * 构造网络消息序列化器。
     */
    public NetMessageSerializer() {
    }

    /**
     * 将字节数组转换为NetMessage对象。
     *
     * @param bytes 字节数组
     * @return NetMessage对象
     * @throws IllegalArgumentException 如果解析失败
     */
    private NetMessage toMsg(byte[] bytes) {
        try {
            return NetMessage.parseFrom(bytes);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid NetMessage payload", exception);
        }
    }

    /**
     * 将对象序列化为字节数组。
     *
     * @param arg0 待序列化对象，必须是NetMessage类型
     * @return 序列化后的字节数组
     * @throws IllegalArgumentException 如果对象不是NetMessage类型
     */
    @Override
    public byte[] toBinary(Object arg0) {
        if (!(arg0 instanceof NetMessage message)) {
            throw new IllegalArgumentException("NetMessageSerializer accepts NetMessage only");
        }
        return message.toByteArray();
    }

    /**
     * 从字节数组反序列化为对象。
     *
     * @param arg0 字节数组
     * @return 反序列化后的NetMessage对象
     */
    @Override
    public Object fromBinary(byte[] arg0) {
        return toMsg(arg0);
    }

    /**
     * 从字节数组反序列化为对象，带类型选项。
     *
     * @param arg0 字节数组
     * @param arg1 类型选项
     * @return 反序列化后的NetMessage对象
     */
    @Override
    public Object fromBinary(byte[] arg0, Option<Class<?>> arg1) {
        return toMsg(arg0);
    }

    /**
     * 从字节数组反序列化为对象，带类型信息。
     *
     * @param arg0 字节数组
     * @param arg1 类型信息
     * @return 反序列化后的NetMessage对象
     */
    @Override
    public Object fromBinary(byte[] arg0, Class<?> arg1) {
        return toMsg(arg0);
    }

    @Override
    public int identifier() {
        return 70;
    }

    @Override
    public boolean includeManifest() {
        return false;
    }
}
