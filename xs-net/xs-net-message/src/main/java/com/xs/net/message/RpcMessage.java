package com.xs.net.message;

import com.google.protobuf.MessageLite;

/**
 * RPC消息接口，定义RPC消息的基本结构。
 * 包含RPC编号和消息负载。
 */
public interface RpcMessage {
    /**
     * 获取RPC编号。
     *
     * @return RPC编号
     */
    int rpcNum();

    /**
     * 获取消息负载。
     *
     * @return Protocol Buffers消息对象
     */
    MessageLite payload();
}
