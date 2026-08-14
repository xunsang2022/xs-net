package com.game.logic.log;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LogType {

    LOGIN(0, "login"),
    BATTLE(1, "battle"),
    TRADE(2, "trade"),
    CHAT(3, "chat"),
    SYSTEM(4, "system"),
    OTHER(5, "other");

    private final int value;
    private final String description;

    public static LogType valueOf(int value) {
        for (LogType type : values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid LogType value: " + value);
    }
}
