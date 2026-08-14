package com.game.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 货币类型枚举
 */
@Getter
@AllArgsConstructor
public enum CurrencyType {
    /**
     * 金币 - 游戏内货币
     */
    GOLD(0, "金币"),
    
    /**
     * 宝石 - 付费货币
     */
    GEM(1, "宝石");
    
    private final int value;
    private final String description;
    
    /**
     * 根据 value 获取 CurrencyType
     */
    public static CurrencyType valueOf(int value) {
        for (CurrencyType type : values()) {
            if (type.getValue() == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid CurrencyType value: " + value);
    }
}
