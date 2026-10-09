package com.game.logic.context;

import com.game.eventsourcing.DBSyncEngine;
import com.game.eventsourcing.LogConsumer;
import com.game.logic.redis.RedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ExecutorService;

/**
 * 非 Spring Boot逻辑节点启动流程的静态接线点。
 */
public final class LogicContext {
    @Setter
    private static volatile RedisConnectionFactory connectionFactory;
    @Setter
    private static volatile StringRedisTemplate redisTemplate;
    @Setter
    private static volatile RedisStore redisStore;
    @Setter
    private static volatile DBSyncEngine dbSyncEngine;
    @Setter
    private static volatile LogConsumer logConsumer;
    @Setter
    private static volatile MeterRegistry meterRegistry;
    @Setter
    private static volatile LogicMetrics logicMetrics;
    @Setter
    private static volatile ExecutorService redisIoExecutor;
    @Setter
    private static volatile ExecutorService dbIoExecutor;
    @Getter
    private static volatile long syncIntervalMs = 1000L;

    private LogicContext() {
    }

    public static RedisConnectionFactory getConnectionFactory() {
        RedisConnectionFactory factory = connectionFactory;
        if (factory == null) {
            throw new IllegalStateException("RedisConnectionFactory has not been initialized");
        }
        return factory;
    }

    public static StringRedisTemplate getRedisTemplate() {
        StringRedisTemplate template = redisTemplate;
        if (template == null) {
            throw new IllegalStateException("StringRedisTemplate has not been initialized");
        }
        return template;
    }

    public static DBSyncEngine getDbSyncEngine() {
        DBSyncEngine engine = dbSyncEngine;
        if (engine == null) {
            throw new IllegalStateException("DBSyncEngine has not been initialized");
        }
        return engine;
    }

    public static LogConsumer getLogConsumer() {
        LogConsumer consumer = logConsumer;
        if (consumer == null) {
            throw new IllegalStateException("LogConsumer has not been initialized");
        }
        return consumer;
    }

    public static RedisStore getRedisStore() {
        RedisStore store = redisStore;
        if (store == null) {
            throw new IllegalStateException("RedisStore has not been initialized");
        }
        return store;
    }

    public static ExecutorService getRedisIoExecutor() {
        ExecutorService executor = redisIoExecutor;
        if (executor == null) {
            throw new IllegalStateException("Redis IO executor has not been initialized");
        }
        return executor;
    }

    public static ExecutorService getDbIoExecutor() {
        ExecutorService executor = dbIoExecutor;
        if (executor == null) {
            throw new IllegalStateException("DB IO executor has not been initialized");
        }
        return executor;
    }

    public static void setSyncIntervalMs(long intervalMs) {
        syncIntervalMs = Math.max(1L, intervalMs);
    }

    public static MeterRegistry getMeterRegistry() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            throw new IllegalStateException("MeterRegistry has not been initialized");
        }
        return registry;
    }

    public static LogicMetrics getLogicMetrics() {
        LogicMetrics metrics = logicMetrics;
        if (metrics == null) {
            throw new IllegalStateException("LogicMetrics has not been initialized");
        }
        return metrics;
    }

    /** 供 DBSyncEngine 等可选上报场景使用：未装配时返回 null。 */
    public static LogicMetrics getLogicMetricsOrNull() {
        return logicMetrics;
    }
}
