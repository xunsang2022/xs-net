package com.game.logic.log;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogLevel {

    HIGH(0, "high"),
    MEDIUM(1, "medium"),
    LOW(2, "low");

    private final int value;
    private final String description;

    public static LogLevel valueOf(int value) {
        for (LogLevel level : values()) {
            if (level.getValue() == value) {
                return level;
            }
        }
        throw new IllegalArgumentException("Invalid LogLevel value: " + value);
    }
}
