package com.game;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import com.game.logic.actor.BattleCommandIngress;
import com.game.logic.actor.BattleExecutorConfig;
import com.game.logic.actor.BattleExecutorPool;
import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.config.LogbackLevelUpdater;
import com.game.logic.config.LogicRuntimeConfiguration;
import com.game.logic.dbsync.DBSyncConfig;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.redis.RedisStore;
import com.game.logic.metrics.LogicPrometheusServer;
import com.game.logic.management.GmProtocolTraceService;
import com.game.logic.management.ProcessArthasTraceClient;
import com.game.logic.management.ProtocolTraceTargetResolver;
import com.game.message.actor.logic.BattleMessage;
import com.game.message.rpc.GameRpcMessages;
import com.xs.core.actor.node.NodeActor;
import com.xs.core.akka.AkkaKit;
import com.xs.core.config.GameFrameworkProperties;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain Java entry point for the logic node. Pekko, Redis and the in-process
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
        redisConfig.setHostName(config("game.redis.host", "REDIS_HOST", DEFAULT_HOST));
        redisConfig.setPort(parseInt("game.redis.port", "REDIS_PORT", DEFAULT_PORT));
        redisConfig.setDatabase(parseInt("game.redis.database", "REDIS_DATABASE", DEFAULT_DATABASE));
        String password = config("game.redis.password", "REDIS_PASSWORD", DEFAULT_PASSWORD);
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

        RedisStore redisStore = new RedisStore(redisTemplate);
        LogicIoExecutors ioExecutors = LogicIoExecutors.fixed(
                parseInt("game.io.redis-threads", "REDIS_IO_THREADS", "16"),
                parseInt("game.io.db-threads", "DB_IO_THREADS", "8"));
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setRedisIoExecutor(ioExecutors.redisIoExecutor());
        LogicContext.setDbIoExecutor(ioExecutors.dbIoExecutor());
        LogicContext.setSyncIntervalMs(parseLong("game.storage.sync-interval-ms", "SYNC_INTERVAL_MS", DEFAULT_SYNC_INTERVAL_MS));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config("game.db.url", "DB_URL", DEFAULT_DB_URL));
        hikariConfig.setUsername(config("game.db.username", "DB_USERNAME", DEFAULT_DB_USERNAME));
        hikariConfig.setPassword(config("game.db.password", "DB_PASSWORD", DEFAULT_PASSWORD));
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setConnectionTimeout(parseLong("game.db.connection-timeout-ms", "DB_CONNECTION_TIMEOUT_MS", "5000"));
        hikariConfig.setInitializationFailTimeout(-1);
        hikariConfig.setMaximumPoolSize(parseInt("game.db.max-pool-size", "DB_MAX_POOL_SIZE", "10"));
        hikariConfig.setMinimumIdle(parseInt("game.db.min-idle", "DB_MIN_IDLE", "2"));
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DBSyncConfig dbSyncConfig = new DBSyncConfig();
        dbSyncConfig.setBatchSize(parseInt("game.db.batch-size", "DB_BATCH_SIZE", "100"));
        dbSyncConfig.setBgsaveSeconds(parseInt("game.db.bgsave-seconds", "DB_BGSAVE_SECONDS", "3"));
        dbSyncConfig.setFlushConcurrency(parseInt("game.db.flush-concurrency", "DB_FLUSH_CONCURRENCY", "4"));
        DBSyncEngine dbSyncEngine = new DBSyncEngine(jdbcTemplate, dbSyncConfig);
        dbSyncEngine.start();
        LogicContext.setDbSyncEngine(dbSyncEngine);

        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        LogicMetrics logicMetrics = new LogicMetrics(meterRegistry, dbSyncEngine);
        LogicContext.setMeterRegistry(meterRegistry);
        LogicContext.setLogicMetrics(logicMetrics);

        MessageAble.initialize(GameRpcMessages.INSTANCE);
        BattleExecutorConfig battleConfig = new BattleExecutorConfig(
                parseInt("game.battle.executor.pool-size", "BATTLE_EXECUTOR_POOL", "8"),
                parseInt("game.battle.executor.control-queue-capacity", "BATTLE_CONTROL_QUEUE_CAPACITY", "256"),
                parseInt("game.battle.executor.max-inputs-per-drain", "BATTLE_MAX_INPUTS_PER_DRAIN", "512"),
                parseInt("game.battle.executor.shutdown-timeout-ms", "BATTLE_SHUTDOWN_TIMEOUT_MS", "2000"),
                parseInt("game.battle.tick-hz", "BATTLE_TICK_HZ", "60"));
        BattleExecutorPool battleExecutorPool = new BattleExecutorPool(battleConfig);
        BattleCommandIngress battleCommandIngress = new BattleCommandIngress(battleExecutorPool);
        LogicContext.setBattleExecutorPool(battleExecutorPool);
        LogicContext.setBattleCommandIngress(battleCommandIngress);
        AkkaKit.registerDeliveryInterceptor(BattleMessage.class, battleCommandIngress);
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

        LogConsumer logConsumer = new LogConsumer(redisTemplate, jdbcTemplate, dbSyncConfig);
        logConsumer.start();
        LogicContext.setLogConsumer(logConsumer);

        Behavior<Void> guardian = Behaviors.setup(context -> {
            context.spawn(NodeActor.create(properties), "node");
            return Behaviors.empty();
        });
        ActorSystem<Void> system = ActorSystem.create(guardian, "game-system");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AkkaKit.unregisterDeliveryInterceptor(BattleMessage.class);
            battleExecutorPool.close();
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
