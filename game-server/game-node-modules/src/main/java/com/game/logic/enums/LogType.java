package com.game.logic.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 日志类型枚举
 */
@Getter
@AllArgsConstructor
public enum LogType {
    /**
     * 登录日志 - 玩家登录、登出
     */
    LOGIN(0, "登录日志"),

    /**
     * 战斗日志 - 战斗开始、结束、结果
     */
    BATTLE(1, "战斗日志"),

    /**
     * 交易日志 - 物品交易、金币变动
     */
    TRADE(2, "交易日志"),

    /**
     * 聊天日志 - 聊天消息、屏蔽记录
     */
    CHAT(3, "聊天日志"),

    /**
     * 系统日志 - 服务器启停、配置变更
     */
    SYSTEM(4, "系统日志"),

    /**
     * 其他日志 - 未分类的日志
     */
    OTHER(5, "其他日志");

    private final int value;
    private final String description;

    /**
     * 根据 value 获取 LogType
     */
    public static LogType valueOf(int value) {
        for (LogType type : values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid LogType value: " + value);
    }
}
