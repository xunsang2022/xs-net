package com.xs.net.message;

/**
 * 路由值提取器函数式接口，用于从消息对象中提取路由键值。
 * 支持按玩家ID或频道ID进行消息路由。
 */
@FunctionalInterface
public interface RouteValueExtractor {
    /**
     * 从消息对象中提取路由值。
     *
     * @param message 消息对象
     * @return 路由值字符串
     */
    String extract(Object message);
}
