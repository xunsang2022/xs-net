package com.xs.net.message;

import com.google.protobuf.MessageLite;

/**
 * RPC信封工厂接口，用于创建RPC消息信封。
 */
public interface RpcEnvelopeFactory {
    /**
     * 创建RPC消息信封。
     *
     * @param rpcNum RPC编号
     * @param payload Protocol Buffers消息负载
     * @return RPC消息对象
     */
    RpcMessage create(int rpcNum, MessageLite payload);
}
