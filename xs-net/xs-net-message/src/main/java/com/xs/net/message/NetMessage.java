package com.xs.net.message;

import com.baidu.bjf.remoting.protobuf.annotation.ProtobufClass;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 网络消息
 * <p/>
 * 网关用一下
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@ProtobufClass
public class NetMessage implements INetMessage  {
    /**
     * rpc编号
     */
    public int rpcNum;

    /**
     * 数据
     */
    byte[] data;

    /**
     * 错误码
     */
    int errorCode;

    /**
     * sessionId
     */
    int sessionId;

    /**
     * 用户ID
     */
    int userId;

    /**
     * 用户IP
     */
    String userIp;


    public NetMessage(int rpcNum) {
        this.rpcNum = rpcNum;
        this.errorCode = 0;
    }

    public NetMessage(int rpcNum, byte[] data) {
        this.rpcNum = rpcNum;
        this.errorCode = 0;
        this.data = data;
    }
}
