package com.game.logic.config;

import com.game.eventsourcing.sync.DBSyncEngineRegistry;
import com.game.eventsourcing.log.LogConsumer;
import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.management.GmProtocolTraceService;
import com.game.logic.management.ProcessArthasTraceClient;
import com.game.logic.management.ProtocolTraceTargetResolver;
import com.game.logic.metrics.LogicPrometheusServer;
import com.game.logic.redis.RedisStore;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.ActorSystem;
import org.springframework.beans.factory.DisposableBean;
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
@RequiredArgsConstructor
public class LogicNodeRuntime implements ApplicationRunner, AutoCloseable, DisposableBean {
    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisStore redisStore;
    private final LogicIoExecutors ioExecutors;
    private final DBSyncEngineRegistry dbSyncEngineRegistry;
    private final LogConsumer logConsumer;
    private final PrometheusMeterRegistry meterRegistry;
    private final LogicMetrics logicMetrics;
    private final LogicNodeProperties properties;
    private final ObjectProvider<ActorSystem<Void>> actorSystemProvider;

    private volatile ActorSystem<Void> actorSystem;
    private volatile LogicPrometheusServer prometheusServer;
    private volatile LogicRuntimeConfiguration runtimeConfiguration;

    /**
     * Spring Boot 启动入口，按顺序初始化逻辑节点所有核心组件。
     * <ol>
     *   <li>将 Redis、线程池、同步引擎、监控等基础组件注入 {@link LogicContext} 静态上下文</li>
     *   <li>启动 DB 同步引擎、RPC 消息协议、管理端点、运行时配置、日志消费者</li>
     *   <li>创建 Pekko Actor 系统，正式进入游戏逻辑处理</li>
     * </ol>
     *
     * @param args 命令行参数（未使用）
     * @throws Exception 初始化过程中任何组件启动失败
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        LogicContext.setConnectionFactory(connectionFactory);
        LogicContext.setRedisTemplate(redisTemplate);
        LogicContext.setRedisStore(redisStore);
        LogicContext.setRedisIoExecutor(ioExecutors.redisIoExecutor());
        LogicContext.setDbIoExecutor(ioExecutors.dbIoExecutor());
        LogicContext.setDbSyncEngineRegistry(dbSyncEngineRegistry);
        LogicContext.setMeterRegistry(meterRegistry);
        LogicContext.setLogicMetrics(logicMetrics);

        dbSyncEngineRegistry.startAll();
        MessageAble.initialize(GameRpcMessages.INSTANCE);
        startManagementEndpoints();
        startRuntimeConfiguration();
        logConsumer.start();
        LogicContext.setLogConsumer(logConsumer);

        actorSystem = actorSystemProvider.getObject();
    }

    /**
     * 阻塞等待 Actor 系统终止。
     * <p>调用此方法会一直阻塞直到 Actor 系统完全关闭，通常用于主线程等待应用退出。</p>
     *
     * @throws IllegalStateException 如果 Actor 系统尚未启动
     */
    public void awaitTermination() {
        ActorSystem<Void> system = actorSystem;
        if (system == null) {
            throw new IllegalStateException("Logic node has not been started");
        }
        system.getWhenTerminated().toCompletableFuture().join();
    }

    /**
     * 启动管理端点，包括 Prometheus 监控服务和 Arthas 协议追踪。
     * <p>创建 GM 协议追踪服务（基于 Arthas），并启动 Prometheus HTTP 端点暴露监控指标。</p>
     *
     * @throws IllegalStateException 如果 Prometheus 端点启动失败（如端口被占用）
     */
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

    /**
     * 启动运行时配置加载。
     * <p>从系统属性中加载 {@link LogicRuntimeConfiguration}，并按配置的间隔定期重新加载，
     * 支持运行时动态调整参数（如日志级别）。</p>
     */
    private void startRuntimeConfiguration() {
        runtimeConfiguration = LogicRuntimeConfiguration.fromSystemProperty(this::applyRuntimeConfiguration);
        if (runtimeConfiguration != null) {
            runtimeConfiguration.start(properties.getManagement().getConfigReloadIntervalMs());
        }
    }

    /**
     * 获取 Arthas 诊断工具的安装目录路径。
     * <p>优先读取系统属性 {@code arthas.home}，若未设置或为空，则回退到配置文件中的指定路径。</p>
     *
     * @return Arthas 安装目录的 Path 对象
     */
    private Path arthasHome() {
        String home = System.getProperty("arthas.home");
        if (home == null || home.isBlank()) {
            home = properties.getManagement().getArthas().getHome();
        }
        return Path.of(home);
    }

    /**
     * 应用运行时配置变更。
     * <p>处理动态配置更新：</p>
     * <ul>
     *   <li>{@code game.logging.level} - 应用日志级别</li>
     * </ul>
     *
     * @param settings 配置键值对映射
     */
    private void applyRuntimeConfiguration(Map<String, String> settings) {
        LogbackLevelUpdater.updateApplicationLevel(settings.get("game.logging.level"));
    }

    /**
     * 关闭逻辑节点，释放所有资源。
     * <p>按顺序执行以下清理操作：</p>
     * <ol>
     *   <li>终止 Actor 系统并等待其完全关闭</li>
     *   <li>停止日志消费者</li>
     *   <li>关闭 IO 线程池</li>
     *   <li>刷新并停止 DB 同步引擎</li>
     *   <li>关闭运行时配置加载器</li>
     *   <li>关闭 Prometheus 监控端点</li>
     * </ol>
     */
    @Override
    public void close() {
        ActorSystem<Void> system = actorSystem;
        if (system != null) {
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
        }
        logConsumer.stop();
        ioExecutors.close();
        dbSyncEngineRegistry.flushAll();
        dbSyncEngineRegistry.stopAll();
        LogicRuntimeConfiguration configuration = runtimeConfiguration;
        if (configuration != null) {
            configuration.close();
        }
        LogicPrometheusServer server = prometheusServer;
        if (server != null) {
            server.close();
        }
    }

    /**
     * Spring 容器关闭回调：委托 {@link #close()} 释放资源。
     * <p>容器扫描的组件不会自动推断 destroy 方法，显式实现 {@link DisposableBean}
     * 才能保证关闭时终止 ActorSystem、停止线程池（如 DBSyncEngine 的调度/分片池）等。</p>
     */
    @Override
    public void destroy() {
        close();
    }
}