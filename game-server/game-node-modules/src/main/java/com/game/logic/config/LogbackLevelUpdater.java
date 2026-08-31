package com.game.logic.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

@Slf4j
public final class LogbackLevelUpdater {

    private LogbackLevelUpdater() {
    }

    public static void updateApplicationLevel(String configuredLevel) {
        if (configuredLevel == null || configuredLevel.isBlank()) {
            return;
        }
        try {
            Class<?> contextType = Class.forName("ch.qos.logback.classic.LoggerContext");
            Object context = LoggerFactory.getILoggerFactory();
            if (!contextType.isInstance(context)) {
                log.warn("Runtime log level update is unavailable because Logback is not active");
                return;
            }
            Class<?> levelType = Class.forName("ch.qos.logback.classic.Level");
            Method toLevel = levelType.getMethod("toLevel", String.class);
            Object level = toLevel.invoke(null, configuredLevel.strip().toUpperCase());
            Object logger = contextType.getMethod("getLogger", String.class).invoke(context, "com.game");
            logger.getClass().getMethod("setLevel", levelType).invoke(logger, level);
            log.info("Updated com.game log level to {}", configuredLevel.strip().toUpperCase());
        } catch (Exception exception) {
            log.warn("Failed to update runtime log level to {}", configuredLevel, exception);
        }
    }
}
