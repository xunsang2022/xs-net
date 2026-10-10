package com.game.eventsourcing.log;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 日志级别枚举
 */
@Getter
@AllArgsConstructor
public enum LogLevel {
    /**
     * 高级别 - 严重错误、系统故障、安全事件
     */
    HIGH(0, "高级别"),

    /**
     * 中级别 - 重要业务操作、警告信息、异常处理
     */
    MEDIUM(1, "中级别"),

    /**
     * 低级别 - 普通信息、调试数据、访问记录
     */
    LOW(2, "低级别");

    private final int value;
    private final String description;

    /**
     * 根据 value 获取 LogLevel
     */
    public static LogLevel valueOf(int value) {
        for (LogLevel level : values()) {
            if (level.getValue() == value) {
                return level;
            }
        }
        throw new IllegalArgumentException("Invalid LogLevel value: " + value);
    }
}
