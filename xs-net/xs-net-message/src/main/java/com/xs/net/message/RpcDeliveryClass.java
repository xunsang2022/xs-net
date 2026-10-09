package com.xs.net.message;

/**
 * RPC消息传递类型枚举，定义消息的传递方式和持久化策略。
 */
public enum RpcDeliveryClass {
    /** 实时消息，不保证送达 */
    REALTIME,
    /** 响应消息，用于请求-响应模式 */
    RESPONSE,
    /** 状态消息，只保留最新状态 */
    STATE_LATEST,
    /** 持久化事件，保证送达且按顺序处理 */
    DURABLE_EVENT
}
