package com.game;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import com.game.logic.context.LogicContext;
import com.game.logic.dbsync.DBSyncConfig;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.redis.RedisStore;
import com.game.message.rpc.GameRpcEnvelopeFactory;
import com.game.message.rpc.GameRpcMessages;
import com.xs.core.actor.node.NodeActor;
import com.xs.core.config.GameFrameworkProperties;
import com.xs.net.message.MessageAble;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plain Java entry point for the logic node. Akka, Redis and the in-process
 * DBSyncEngine are wired manually instead of through Spring Boot.
 */
public final class GameLogicApplication {

    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "6379";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_DATABASE = "0";
    private static final String DEFAULT_SYNC_INTERVAL_MS = "1000";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/gamedb";
    private static final String DEFAULT_DB_USERNAME = "root";

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

        RedisStore redisStore = new RedisStore(redisTemplate);
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setSyncIntervalMs(parseLong("game.storage.sync-interval-ms", "SYNC_INTERVAL_MS", DEFAULT_SYNC_INTERVAL_MS));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config("game.db.url", "DB_URL", DEFAULT_DB_URL));
        hikariConfig.setUsername(config("game.db.username", "DB_USERNAME", DEFAULT_DB_USERNAME));
        hikariConfig.setPassword(config("game.db.password", "DB_PASSWORD", DEFAULT_PASSWORD));
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DBSyncConfig dbSyncConfig = new DBSyncConfig();
        dbSyncConfig.setBatchSize(parseInt("game.db.batch-size", "DB_BATCH_SIZE", "100"));
        dbSyncConfig.setBgsaveSeconds(parseInt("game.db.bgsave-seconds", "DB_BGSAVE_SECONDS", "3"));
        dbSyncConfig.setFlushConcurrency(parseInt("game.db.flush-concurrency", "DB_FLUSH_CONCURRENCY", "4"));
        DBSyncEngine dbSyncEngine = new DBSyncEngine(jdbcTemplate, dbSyncConfig);
        dbSyncEngine.start();
        LogicContext.setDbSyncEngine(dbSyncEngine);

        LogConsumer logConsumer = new LogConsumer(redisTemplate, jdbcTemplate, dbSyncConfig);
        logConsumer.start();
        LogicContext.setLogConsumer(logConsumer);

        MessageAble.initialize(GameRpcMessages.INSTANCE, new GameRpcEnvelopeFactory());

        Behavior<Void> guardian = Behaviors.setup(context -> {
            context.spawn(NodeActor.create(properties), "node");
            return Behaviors.empty();
        });
        ActorSystem<Void> system = ActorSystem.create(guardian, "game-system");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            system.terminate();
            logConsumer.stop();
            dbSyncEngine.stop();
            connectionFactory.destroy();
            dataSource.close();
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
