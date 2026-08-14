package com.game;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import com.game.logic.context.LogicContext;
import com.game.logic.redis.RedisDbSyncNotifier;
import com.game.logic.redis.RedisStore;
import com.xs.core.actor.node.NodeActor;
import com.xs.core.config.GameFrameworkProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Plain Java entry point for the logic node. Akka and Redis are wired
 * manually instead of through Spring Boot.
 */
public final class GameLogicApplication {

    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "6379";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_DATABASE = "0";
    private static final String DEFAULT_SYNC_INTERVAL_MS = "1000";

    private GameLogicApplication() {
    }

    public static void main(String[] args) {
        GameFrameworkProperties properties = new GameFrameworkProperties();
        properties.setActorBasePackages(new String[]{"com.game", "com.game.logic", "com.game.message"});
        properties.setMessageBasePackages(new String[]{"com.game", "com.game.logic", "com.game.message"});

        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(config("game.redis.host", "REDIS_HOST", DEFAULT_HOST));
        redisConfig.setPort(parseInt("game.redis.port", "REDIS_PORT", DEFAULT_PORT));
        redisConfig.setDatabase(parseInt("game.redis.database", "REDIS_DATABASE", DEFAULT_DATABASE));
        String password = config("game.redis.password", "REDIS_PASSWORD", DEFAULT_PASSWORD);
        if (!password.isBlank()) {
            redisConfig.setPassword(password);
        }

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        RedisDbSyncNotifier notifier = new RedisDbSyncNotifier();
        RedisStore redisStore = new RedisStore(redisTemplate, notifier);
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setDbSyncNotifier(notifier);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setSyncIntervalMs(parseLong("game.storage.sync-interval-ms", "SYNC_INTERVAL_MS", DEFAULT_SYNC_INTERVAL_MS));

        Behavior<Void> guardian = Behaviors.setup(context -> {
            context.spawn(NodeActor.create(properties), "node");
            return Behaviors.empty();
        });
        ActorSystem<Void> system = ActorSystem.create(guardian, "game-system");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            system.terminate();
            LogicContext.getConnectionFactory().destroy();
        }, "logic-shutdown"));

        system.getWhenTerminated().toCompletableFuture().join();
    }

    private static String config(String key, String envKey, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int parseInt(String key, String envKey, String defaultValue) {
        return Integer.parseInt(config(key, envKey, defaultValue));
    }

    private static long parseLong(String key, String envKey, String defaultValue) {
        return Long.parseLong(config(key, envKey, defaultValue));
    }
}
