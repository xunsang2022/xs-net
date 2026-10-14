package com.game.logic.context;

import com.game.eventsourcing.sync.DBSyncEngineRegistry;
import com.game.eventsourcing.log.LogConsumer;
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
    private static volatile DBSyncEngineRegistry dbSyncEngineRegistry;
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
    private static volatile int syncIntervalMs = 1000;

    private LogicContext() {
    }

    public static DBSyncEngineRegistry getDbSyncEngineRegistry() {
        DBSyncEngineRegistry registry = dbSyncEngineRegistry;
        if (registry == null) {
            throw new IllegalStateException("DBSyncEngineRegistry has not been initialized");
        }
        return registry;
    }

    /** 返回登录指标；未装配时返回 null，调用方需安全跳过。 */
    public static LogicMetrics getLogicMetrics() {
        return logicMetrics;
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

    public static void setSyncIntervalMs(int intervalMs) {
        syncIntervalMs = Math.max(1, intervalMs);
    }
}