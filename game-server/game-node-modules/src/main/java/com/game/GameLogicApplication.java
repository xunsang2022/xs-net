package com.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.config.LogbackLevelUpdater;
import com.game.logic.config.LogicRuntimeConfiguration;
import com.game.logic.dbsync.DBSyncConfig;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.dbsync.MybatisPersistenceConfiguration;
import com.game.logic.dbsync.PlayerPersistence;
import com.game.logic.redis.RedisStore;
import com.game.logic.metrics.LogicPrometheusServer;
import com.game.logic.management.GmProtocolTraceService;
import com.game.logic.management.ProcessArthasTraceClient;
import com.game.logic.management.ProtocolTraceTargetResolver;
import com.game.message.rpc.GameRpcMessages;
import com.xs.kernel.actor.node.NodeActor;
import com.xs.kernel.config.GameFrameworkProperties;
import com.xs.net.message.MessageAble;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain Java entry point for the logic node. Pekko, Redis and the in-process
 * DBSyncEngine and MyBatis-Plus are wired manually instead of through Spring Boot.
 */
public final class GameLogicApplication {

    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_PORT = "6379";
    private static final String DEFAULT_PASSWORD = "";
    private static final String DEFAULT_DATABASE = "0";
    private static final String DEFAULT_SYNC_INTERVAL_MS = "1000";
    private static final String DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/gamedb";
    private static final String DEFAULT_DB_USERNAME = "root";

    private static final Map<String, String> YML_CONFIG = new HashMap<>();

    static {
        try (InputStream in = GameLogicApplication.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (in != null) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(in);
                if (root != null) {
                    flattenYaml("", root, YML_CONFIG);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private GameLogicApplication() {
    }

    public static void main(String[] args) {
        GameFrameworkProperties properties = new GameFrameworkProperties();
        properties.setActorBasePackages(new String[]{"com.game.logic.actor", "com.game.message.actor.logic", "com.game.message.actor"});
        properties.setMessageBasePackages(new String[]{"com.game.message.actor.logic", "com.game.message.actor"});

        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(fileConfig("game.redis.host", DEFAULT_HOST));
        redisConfig.setPort(parseFileInt("game.redis.port", DEFAULT_PORT));
        redisConfig.setDatabase(parseFileInt("game.redis.database", DEFAULT_DATABASE));
        String password = fileConfig("game.redis.password", DEFAULT_PASSWORD);
        if (!password.isBlank()) {
            redisConfig.setPassword(password);
        }

        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(32);
        poolConfig.setMinIdle(4);
        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .build();
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfig, clientConfig);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        RedisTemplate<String, Object> playerRedisTemplate = createPlayerRedisTemplate(connectionFactory);

        RedisStore redisStore = new RedisStore(redisTemplate, playerRedisTemplate);
        LogicIoExecutors ioExecutors = LogicIoExecutors.fixed(
                parseFileInt("game.io.redis-threads", "16"),
                parseFileInt("game.io.db-threads", "8"));
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setRedisIoExecutor(ioExecutors.redisIoExecutor());
        LogicContext.setDbIoExecutor(ioExecutors.dbIoExecutor());
        LogicContext.setSyncIntervalMs(parseFileLong("game.storage.sync-interval-ms", DEFAULT_SYNC_INTERVAL_MS));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(fileConfig("game.db.url", DEFAULT_DB_URL));
        hikariConfig.setUsername(fileConfig("game.db.username", DEFAULT_DB_USERNAME));
        hikariConfig.setPassword(fileConfig("game.db.password", DEFAULT_PASSWORD));
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setConnectionTimeout(parseFileLong("game.db.connection-timeout-ms", "5000"));
        hikariConfig.setInitializationFailTimeout(-1);
        hikariConfig.setMaximumPoolSize(parseFileInt("game.db.max-pool-size", "10"));
        hikariConfig.setMinimumIdle(parseFileInt("game.db.min-idle", "2"));
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        var sqlSessionFactory = MybatisPersistenceConfiguration.create(dataSource);
        PlayerPersistence playerPersistence = new PlayerPersistence(sqlSessionFactory);
        DBSyncConfig dbSyncConfig = new DBSyncConfig();
        dbSyncConfig.setBatchSize(parseFileInt("game.db.batch-size", "100"));
        dbSyncConfig.setBgsaveSeconds(parseFileInt("game.db.bgsave-seconds", "3"));
        dbSyncConfig.setFlushConcurrency(parseFileInt("game.db.flush-concurrency", "4"));
        DBSyncEngine dbSyncEngine = new DBSyncEngine(playerPersistence, dbSyncConfig);
        dbSyncEngine.start();
        LogicContext.setDbSyncEngine(dbSyncEngine);

        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        LogicMetrics logicMetrics = new LogicMetrics(meterRegistry, dbSyncEngine);
        LogicContext.setMeterRegistry(meterRegistry);
        LogicContext.setLogicMetrics(logicMetrics);

        MessageAble.initialize(GameRpcMessages.INSTANCE);
        GmProtocolTraceService traceService = new GmProtocolTraceService(
                new ProtocolTraceTargetResolver(GameRpcMessages.INSTANCE),
                new ProcessArthasTraceClient(
                        arthasHome(),
                        ProcessHandle.current().info().command().orElse("java"),
                        parseInt("game.management.arthas.telnet-port", "LOGIC_ARTHAS_TELNET_PORT", "36581")));
        LogicPrometheusServer prometheusServer = startPrometheusServer(meterRegistry, traceService);
        LogicRuntimeConfiguration runtimeConfiguration = LogicRuntimeConfiguration.fromSystemProperty(
                GameLogicApplication::applyRuntimeConfiguration);
        if (runtimeConfiguration != null) {
            runtimeConfiguration.start(parseLong(
                    "game.management.config-reload-interval-ms",
                    "LOGIC_CONFIG_RELOAD_INTERVAL_MS",
                    "1000"));
        }

        LogConsumer logConsumer = new LogConsumer(redisTemplate, sqlSessionFactory, dbSyncConfig);
        logConsumer.start();
        LogicContext.setLogConsumer(logConsumer);

        Behavior<Void> guardian = Behaviors.setup(context -> {
            context.spawn(NodeActor.create(properties), "node");
            return Behaviors.empty();
        });
        ActorSystem<Void> system = ActorSystem.create(guardian, "game-system");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
            logConsumer.stop();
            ioExecutors.close();
            dbSyncEngine.flush();
            dbSyncEngine.stop();
            if (runtimeConfiguration != null) {
                runtimeConfiguration.close();
            }
            prometheusServer.close();
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
        if (value == null || value.isBlank()) {
            value = YML_CONFIG.get(key);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    static String fileConfig(String key, String defaultValue) {
        String value = YML_CONFIG.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int parseFileInt(String key, String defaultValue) {
        return Integer.parseInt(fileConfig(key, defaultValue));
    }

    private static long parseFileLong(String key, String defaultValue) {
        return Long.parseLong(fileConfig(key, defaultValue));
    }

    private static int parseInt(String key, String envKey, String defaultValue) {
        return Integer.parseInt(config(key, envKey, defaultValue));
    }

    private static long parseLong(String key, String envKey, String defaultValue) {
        return Long.parseLong(config(key, envKey, defaultValue));
    }

    private static LogicPrometheusServer startPrometheusServer(
            PrometheusMeterRegistry meterRegistry,
            GmProtocolTraceService traceService
    ) {
        String host = config("game.management.metrics.host", "LOGIC_METRICS_HOST", "127.0.0.1");
        int port = parseInt("game.management.metrics.port", "LOGIC_METRICS_PORT", "9090");
        String managementToken = config("game.management.token", "LOGIC_GM_TOKEN", "");
        try {
            return LogicPrometheusServer.start(host, port, meterRegistry, managementToken, traceService);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Logic Prometheus endpoint on " + host + ':' + port, exception);
        }
    }

    @SuppressWarnings("deprecation")
    private static RedisTemplate<String, Object> createPlayerRedisTemplate(
            LettuceConnectionFactory connectionFactory
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(new ObjectMapper(), Object.class);
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    private static java.nio.file.Path arthasHome() {
        String home = System.getProperty("arthas.home");
        if (home == null || home.isBlank()) {
            home = config("game.management.arthas.home", "LOGIC_ARTHAS_HOME", "");
        }
        return java.nio.file.Path.of(home);
    }

    private static void applyRuntimeConfiguration(Map<String, String> settings) {
        String syncInterval = settings.get("game.storage.sync-interval-ms");
        if (syncInterval != null && !syncInterval.isBlank()) {
            try {
                LogicContext.setSyncIntervalMs(Long.parseLong(syncInterval));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("game.storage.sync-interval-ms must be a positive integer", exception);
            }
        }
        LogbackLevelUpdater.updateApplicationLevel(settings.get("game.logging.level"));
    }

    @SuppressWarnings("unchecked")
    private static void flattenYaml(String prefix, Map<String, Object> map, Map<String, String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) {
                flattenYaml(key, (Map<String, Object>) v, out);
            } else if (v != null) {
                out.put(key, v.toString());
            }
        }
    }
}
