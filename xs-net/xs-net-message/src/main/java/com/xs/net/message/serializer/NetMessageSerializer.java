package com.xs.net.message.serializer;

import org.apache.pekko.serialization.Serializer;
import com.xs.net.message.pb.NetMessage;
import scala.Option;

/// 网络消息序列化
public class NetMessageSerializer implements Serializer {

    public NetMessageSerializer() {
    }

    private NetMessage toMsg(byte[] bytes) {
        try {
            return NetMessage.parseFrom(bytes);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid NetMessage payload", exception);
        }
    }

    @Override
    public byte[] toBinary(Object arg0) {
        if (!(arg0 instanceof NetMessage message)) {
            throw new IllegalArgumentException("NetMessageSerializer accepts NetMessage only");
        }
        return message.toByteArray();
    }

    @Override
    public Object fromBinary(byte[] arg0) {
        return toMsg(arg0);
    }

    @Override
    public Object fromBinary(byte[] arg0, Option<Class<?>> arg1) {
        return toMsg(arg0);
    }

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
