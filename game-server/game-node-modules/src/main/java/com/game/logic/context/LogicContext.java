package com.game.logic.context;

import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.actor.BattleCommandIngress;
import com.game.logic.actor.BattleExecutorPool;
import com.game.logic.redis.RedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.ExecutorService;

/**
 * Static wiring point for the non-Spring-Boot logic node startup flow.
 */
public final class LogicContext {

    private static volatile RedisConnectionFactory connectionFactory;
    private static volatile StringRedisTemplate redisTemplate;
    private static volatile RedisStore redisStore;
    private static volatile DBSyncEngine dbSyncEngine;
    private static volatile LogConsumer logConsumer;
    private static volatile MeterRegistry meterRegistry;
    private static volatile LogicMetrics logicMetrics;
    private static volatile ExecutorService redisIoExecutor;
    private static volatile ExecutorService dbIoExecutor;
    private static volatile BattleExecutorPool battleExecutorPool;
    private static volatile BattleCommandIngress battleCommandIngress;
    private static volatile long syncIntervalMs = 1000L;

    private LogicContext() {
    }

    public static void setConnectionFactory(RedisConnectionFactory factory) {
        connectionFactory = factory;
    }

    public static RedisConnectionFactory getConnectionFactory() {
        RedisConnectionFactory factory = connectionFactory;
        if (factory == null) {
            throw new IllegalStateException("RedisConnectionFactory has not been initialized");
        }
        return factory;
    }

    public static void setRedisTemplate(StringRedisTemplate template) {
        redisTemplate = template;
    }

    public static StringRedisTemplate getRedisTemplate() {
        StringRedisTemplate template = redisTemplate;
        if (template == null) {
            throw new IllegalStateException("StringRedisTemplate has not been initialized");
        }
        return template;
    }

    public static void setDbSyncEngine(DBSyncEngine engine) {
        dbSyncEngine = engine;
    }

    public static DBSyncEngine getDbSyncEngine() {
        DBSyncEngine engine = dbSyncEngine;
        if (engine == null) {
            throw new IllegalStateException("DBSyncEngine has not been initialized");
        }
        return engine;
    }

    public static void setLogConsumer(LogConsumer consumer) {
        logConsumer = consumer;
    }

    public static LogConsumer getLogConsumer() {
        LogConsumer consumer = logConsumer;
        if (consumer == null) {
            throw new IllegalStateException("LogConsumer has not been initialized");
        }
        return consumer;
    }

    public static void setRedisStore(RedisStore store) {
        redisStore = store;
    }

    public static RedisStore getRedisStore() {
        RedisStore store = redisStore;
        if (store == null) {
            throw new IllegalStateException("RedisStore has not been initialized");
        }
        return store;
    }

    public static void setRedisIoExecutor(ExecutorService executor) {
        redisIoExecutor = executor;
    }

    public static ExecutorService getRedisIoExecutor() {
        ExecutorService executor = redisIoExecutor;
        if (executor == null) {
            throw new IllegalStateException("Redis IO executor has not been initialized");
        }
        return executor;
    }

    public static void setDbIoExecutor(ExecutorService executor) {
        dbIoExecutor = executor;
    }

    public static ExecutorService getDbIoExecutor() {
        ExecutorService executor = dbIoExecutor;
        if (executor == null) {
            throw new IllegalStateException("DB IO executor has not been initialized");
        }
        return executor;
    }

    public static void setBattleExecutorPool(BattleExecutorPool pool) {
        battleExecutorPool = pool;
    }

    public static BattleExecutorPool getBattleExecutorPool() {
        BattleExecutorPool pool = battleExecutorPool;
        if (pool == null) {
            throw new IllegalStateException("Battle executor pool has not been initialized");
        }
        return pool;
    }

    public static void setBattleCommandIngress(BattleCommandIngress ingress) {
        battleCommandIngress = ingress;
    }

    public static BattleCommandIngress getBattleCommandIngress() {
        BattleCommandIngress ingress = battleCommandIngress;
        if (ingress == null) {
            throw new IllegalStateException("Battle command ingress has not been initialized");
        }
        return ingress;
    }

    public static void setSyncIntervalMs(long intervalMs) {
        syncIntervalMs = Math.max(1L, intervalMs);
    }

    public static long getSyncIntervalMs() {
        return syncIntervalMs;
    }

    public static void setMeterRegistry(MeterRegistry registry) {
        meterRegistry = registry;
    }

    public static MeterRegistry getMeterRegistry() {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            throw new IllegalStateException("MeterRegistry has not been initialized");
        }
        return registry;
    }

    public static void setLogicMetrics(LogicMetrics metrics) {
        logicMetrics = metrics;
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
