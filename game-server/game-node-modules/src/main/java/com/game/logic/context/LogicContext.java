package com.game.logic.context;

import com.game.logic.redis.RedisDbSyncNotifier;
import com.game.logic.redis.RedisStore;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Static wiring point for the non-Spring-Boot logic node startup flow.
 */
public final class LogicContext {

    private static volatile RedisConnectionFactory connectionFactory;
    private static volatile StringRedisTemplate redisTemplate;
    private static volatile RedisDbSyncNotifier dbSyncNotifier;
    private static volatile RedisStore redisStore;
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

    public static void setDbSyncNotifier(RedisDbSyncNotifier notifier) {
        dbSyncNotifier = notifier;
    }

    public static RedisDbSyncNotifier getDbSyncNotifier() {
        RedisDbSyncNotifier notifier = dbSyncNotifier;
        if (notifier == null) {
            throw new IllegalStateException("RedisDbSyncNotifier has not been initialized");
        }
        return notifier;
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

    public static void setSyncIntervalMs(long intervalMs) {
        syncIntervalMs = Math.max(1L, intervalMs);
    }

    public static long getSyncIntervalMs() {
        return syncIntervalMs;
    }
}
