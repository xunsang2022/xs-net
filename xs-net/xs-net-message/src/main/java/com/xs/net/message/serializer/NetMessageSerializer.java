package com.xs.net.message.serializer;

import org.apache.pekko.serialization.Serializer;
import com.xs.net.message.MessageAble;
import com.xs.net.message.pb.NetMessage;
import lombok.SneakyThrows;
import scala.Option;

/// 网络消息序列化
public class NetMessageSerializer implements Serializer {

    public NetMessageSerializer() {
    }

    @SneakyThrows
    private Object toMsg(byte[] csByte) {
        NetMessage s_r_cs = MessageAble.toNetMessage(csByte);
        return MessageAble.toActionMessage(s_r_cs);
    }

    @SneakyThrows
    @Override
    public byte[] toBinary(Object arg0) {
        return MessageAble.toNetByteMsg((NetMessage) arg0);
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
