package com.xs.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Game Framework 配置属性
 */
@Data
@ConfigurationProperties(prefix = "game.framework")
public class GameFrameworkProperties {

    /**
     * 是否启用 Actor 自动注册
     */
    private boolean actorAutoRegisterEnabled = true;

    /**
     * 是否启用 ActionMessage 自动注册
     */
    private boolean messageAutoRegisterEnabled = true;

    /**
     * Actor 扫描的基础包路径（可选，为空则扫描所有包）
     */
    private String[] actorBasePackages = {};

    /**
     * ActionMessage 扫描的基础包路径（可选，为空则扫描所有包）
     */
    private String[] messageBasePackages = {};
}
