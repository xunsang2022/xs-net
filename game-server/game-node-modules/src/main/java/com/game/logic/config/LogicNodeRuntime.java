package com.game.logic.config;

import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.management.GmProtocolTraceService;
import com.game.logic.management.ProcessArthasTraceClient;
import com.game.logic.management.ProtocolTraceTargetResolver;
import com.game.logic.metrics.LogicPrometheusServer;
import com.game.logic.redis.RedisStore;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.ActorSystem;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Component
public class LogicNodeRuntime implements ApplicationRunner, AutoCloseable {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisStore redisStore;
    private final LogicIoExecutors ioExecutors;
    private final DBSyncEngine dbSyncEngine;
    private final LogConsumer logConsumer;
    private final PrometheusMeterRegistry meterRegistry;
    private final LogicMetrics logicMetrics;
    private final LogicNodeProperties properties;
    private final ObjectProvider<ActorSystem<Void>> actorSystemProvider;

    private volatile ActorSystem<Void> actorSystem;
    private volatile LogicPrometheusServer prometheusServer;
    private volatile LogicRuntimeConfiguration runtimeConfiguration;

    public LogicNodeRuntime(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            RedisStore redisStore,
            LogicIoExecutors ioExecutors,
            DBSyncEngine dbSyncEngine,
            LogConsumer logConsumer,
            PrometheusMeterRegistry meterRegistry,
            LogicMetrics logicMetrics,
            LogicNodeProperties properties,
            ObjectProvider<ActorSystem<Void>> actorSystemProvider
    ) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.redisStore = redisStore;
        this.ioExecutors = ioExecutors;
        this.dbSyncEngine = dbSyncEngine;
        this.logConsumer = logConsumer;
        this.meterRegistry = meterRegistry;
        this.logicMetrics = logicMetrics;
        this.properties = properties;
        this.actorSystemProvider = actorSystemProvider;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setRedisIoExecutor(ioExecutors.redisIoExecutor());
        LogicContext.setDbIoExecutor(ioExecutors.dbIoExecutor());
        LogicContext.setSyncIntervalMs(properties.getStorage().getSyncIntervalMs());
        LogicContext.setDbSyncEngine(dbSyncEngine);
        LogicContext.setMeterRegistry(meterRegistry);
        LogicContext.setLogicMetrics(logicMetrics);

        dbSyncEngine.start();
        MessageAble.initialize(GameRpcMessages.INSTANCE);
        startManagementEndpoints();
        startRuntimeConfiguration();
        logConsumer.start();
        LogicContext.setLogConsumer(logConsumer);

        actorSystem = actorSystemProvider.getObject();
    }

    public void awaitTermination() {
        ActorSystem<Void> system = actorSystem;
        if (system == null) {
            throw new IllegalStateException("Logic node has not been started");
        }
        system.getWhenTerminated().toCompletableFuture().join();
    }

    private void startManagementEndpoints() {
        GmProtocolTraceService traceService = new GmProtocolTraceService(
                new ProtocolTraceTargetResolver(GameRpcMessages.INSTANCE),
                new ProcessArthasTraceClient(
                        arthasHome(),
                        ProcessHandle.current().info().command().orElse("java"),
                        properties.getManagement().getArthas().getTelnetPort()));
        LogicNodeProperties.Metrics metrics = properties.getManagement().getMetrics();
        try {
            prometheusServer = LogicPrometheusServer.start(
                    metrics.getHost(), metrics.getPort(), meterRegistry,
                    properties.getManagement().getToken(), traceService);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Logic Prometheus endpoint on "
                    + metrics.getHost() + ':' + metrics.getPort(), exception);
        }
    }

    private void startRuntimeConfiguration() {
        runtimeConfiguration = LogicRuntimeConfiguration.fromSystemProperty(this::applyRuntimeConfiguration);
        if (runtimeConfiguration != null) {
            runtimeConfiguration.start(properties.getManagement().getConfigReloadIntervalMs());
        }
    }

    private Path arthasHome() {
        String home = System.getProperty("arthas.home");
        if (home == null || home.isBlank()) {
            home = properties.getManagement().getArthas().getHome();
        }
        return Path.of(home);
    }

    private void applyRuntimeConfiguration(Map<String, String> settings) {
        String syncInterval = settings.get("game.storage.sync-interval-ms");
        if (syncInterval != null && !syncInterval.isBlank()) {
            try {
                LogicContext.setSyncIntervalMs(Long.parseLong(syncInterval));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "game.storage.sync-interval-ms must be a positive integer", exception);
            }
        }
        LogbackLevelUpdater.updateApplicationLevel(settings.get("game.logging.level"));
    }

    @Override
    public void close() {
        ActorSystem<Void> system = actorSystem;
        if (system != null) {
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
        }
        logConsumer.stop();
        ioExecutors.close();
        dbSyncEngine.flush();
        dbSyncEngine.stop();
        LogicRuntimeConfiguration configuration = runtimeConfiguration;
        if (configuration != null) {
            configuration.close();
        }
        LogicPrometheusServer server = prometheusServer;
        if (server != null) {
            server.close();
        }
    }
}
