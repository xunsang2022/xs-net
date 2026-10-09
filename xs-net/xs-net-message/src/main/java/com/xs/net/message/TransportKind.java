package com.xs.net.message;

/**
 * 传输层类型枚举，定义支持的网络传输协议。
 */
public enum TransportKind {
    /** TCP传输协议 */
    TCP,
    /** UDP传输协议 */
    UDP,
    /** WebSocket传输协议 */
    WEBSOCKET
}
