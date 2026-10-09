package com.xs.net.message;

/**
 * RPC消息方向枚举，定义消息的传输方向。
 */
public enum RpcMessageDirection {
    /** 内部消息，服务端内部节点间通信 */
    INTERNAL,
    /** HTTP响应消息 */
    HTTP_RESPONSE
}
