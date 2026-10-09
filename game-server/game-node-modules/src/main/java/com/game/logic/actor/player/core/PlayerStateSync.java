package com.game.logic.actor.player.core;

import com.game.logic.actor.player.message.CacheLoadResult;
import com.game.logic.actor.player.message.DatabaseLoadResult;
import com.game.logic.actor.player.message.DestroyResult;
import com.game.logic.actor.player.message.FlushDirty;
import com.game.logic.actor.player.message.InitialRedisWriteResult;
import com.game.logic.actor.player.message.RedisFlushResult;
import com.game.eventsourcing.DBSyncEngine;
import com.game.logic.context.LogicContext;
import com.game.logic.actor.player.PlayerPO;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.kernel.actor.ActorServices;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 注册并处理玩家 Redis、数据库和异步状态读写同步消息。 */
@Slf4j
public final class PlayerStateSync {

    /** 玩家脏数据定时刷新消息的唯一键。 */
    private static final Object FLUSH_KEY = new Object();

    /** 玩家实体 ID。 */
    private final String playerId;
    /** Actor 上下文和 RPC 能力桥接。 */
    private final ActorServices<PlayerMessage> services;
    /** 玩家脏数据刷新定时器。 */
    private final TimerScheduler<PlayerMessage> timers;
    /** 提供在线状态与玩家状态快照的生命周期组件。 */
    private PlayerLifecycle lifecycle;

    /** Redis 状态访问对象，按首次使用延迟获取。 */
    private RedisStore redisStore;
    /** 当前是否存在未完成的 Redis 脏数据刷写。 */
    private boolean redisFlushPending;
    /** 当前使用的脏数据同步间隔，单位为毫秒。 */
    private long scheduledSyncIntervalMs;

    /** 仅用于同步结果单元测试的无依赖构造器。 */
    PlayerStateSync(String playerId) {
        this.playerId = playerId;
        this.services = null;
        this.timers = null;
    }

    /**
     * 创建生产环境使用的状态同步组件。
     *
     * @param services Actor 基础能力桥接
     * @param playerId 玩家实体 ID
     * @param timers   Actor 定时器调度器
     */
    public PlayerStateSync(ActorServices<PlayerMessage> services, String playerId, TimerScheduler<PlayerMessage> timers) {
        this.services = services;
        this.playerId = playerId;
        this.timers = timers;
        this.scheduledSyncIntervalMs = LogicContext.getSyncIntervalMs();
    }

    /**
     * 注册状态同步相关的定时器消息和异步结果消息。
     *
     * @param builder 玩家 Actor 的消息接收构建器
     */
    public void registerHandlers(ReceiveBuilder<PlayerMessage> builder) {
        builder
                .onMessage(FlushDirty.class, this::onFlushDirty)
                .onMessage(RedisFlushResult.class, this::onRedisFlushResult);
    }

    /** 在 Actor 完成组件创建后绑定生命周期状态提供者。 */
    public void bindLifecycle(PlayerLifecycle lifecycle) {
        if (this.lifecycle != null) {
            throw new IllegalStateException("Player lifecycle is already bound");
        }
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** 从 Redis 异步读取玩家状态，并将结果回投给当前 Actor。 */
    void loadFromCache(Long entityPlayerId) {
        RedisStore store = redisStore();
        ExecutorService executor = LogicContext.getRedisIoExecutor();
        CompletableFuture<Map<String, String>> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    Map<String, String> cached = store.load(playerId);
                    LogicContext.getLogicMetrics().recordLoginRedisLoad(System.nanoTime() - started, !cached.isEmpty());
                    return cached;
                } catch (RuntimeException exception) {
                    LogicContext.getLogicMetrics().recordLoginRedisLoadFailure(System.nanoTime() - started);
                    throw exception;
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            services.context().getSelf().tell(new CacheLoadResult(entityPlayerId, null, exception));
            return;
        }
        services.context().pipeToSelf(future,
                (fields, error) -> new CacheLoadResult(entityPlayerId, fields, error));
    }

    /** 从数据库异步读取或创建玩家状态。 */
    void loadFromDatabase(Long entityPlayerId) {
        DBSyncEngine engine = LogicContext.getDbSyncEngine();
        ExecutorService executor = LogicContext.getDbIoExecutor();
        CompletableFuture<Map<String, String>> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    return engine.loadOrCreate(entityPlayerId);
                } finally {
                    LogicContext.getLogicMetrics().recordLoginDatabaseLoad(System.nanoTime() - started);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            services.context().getSelf().tell(new DatabaseLoadResult(entityPlayerId, null, exception));
            return;
        }
        services.context().pipeToSelf(future,
                (fields, error) -> new DatabaseLoadResult(entityPlayerId, fields, error));
    }

    /** 将玩家首次上线状态异步写入 Redis。 */
    void writeInitialState(Long entityPlayerId, Map<String, String> snapshot) {
        RedisStore store = redisStore();
        ExecutorService executor = LogicContext.getRedisIoExecutor();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    return store.create(playerId, snapshot);
                } finally {
                    LogicContext.getLogicMetrics().recordLoginRedisCreate(System.nanoTime() - started);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            services.context().getSelf().tell(new InitialRedisWriteResult(entityPlayerId, false, exception));
            return;
        }
        services.context().pipeToSelf(future,
                (success, error) -> new InitialRedisWriteResult(entityPlayerId, success, error));
    }

    /** 启动玩家脏数据固定间隔刷新定时器。 */
    void startFlushTimer() {
        if (!timers.isTimerActive(FLUSH_KEY)) {
            timers.startTimerWithFixedDelay(
                    FLUSH_KEY,
                    new FlushDirty(),
                    Duration.ofMillis(scheduledSyncIntervalMs),
                    Duration.ofMillis(scheduledSyncIntervalMs));
        }
    }

    /** 停止玩家脏数据刷新定时器。 */
    void cancelFlushTimer() {
        timers.cancel(FLUSH_KEY);
    }

    /** 将玩家最终快照同步保存到数据库，成功后删除 Redis 缓存。 */
    void destroy(Map<String, String> stateSnapshot, long version) {
        RedisStore store = redisStore();
        DBSyncEngine engine = LogicContext.getDbSyncEngine();
        ExecutorService executor = LogicContext.getRedisIoExecutor();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> engine.saveNow(Long.parseLong(playerId), stateSnapshot, version)
                            && store.deletePlayerCache(playerId), executor);
        } catch (RejectedExecutionException exception) {
            services.context().getSelf().tell(new DestroyResult(stateSnapshot, version, false, exception));
            return;
        }
        services.context().pipeToSelf(future,
                (success, error) -> new DestroyResult(stateSnapshot, version, success, error));
    }

    /** 完成离线状态保存和 Redis 缓存清理。 */
    boolean completeDestroy(DestroyResult result) {
        if (result.error() != null || !Boolean.TRUE.equals(result.success())) {
            log.error("Failed to destroy player state: playerId={}", playerId, result.error());
            return false;
        }
        return true;
    }

    /** Actor 停止时补偿持久化仍需保存的玩家状态。 */
    void persistStopped(Map<String, String> snapshot, long version) {
        RedisStore store = redisStore();
        ExecutorService executor = LogicContext.getRedisIoExecutor();
        DBSyncEngine engine = LogicContext.getDbSyncEngine();
        try {
            CompletableFuture.supplyAsync(
                            () -> engine.saveNow(Long.parseLong(playerId), snapshot, version)
                                    && store.deletePlayerCache(playerId), executor)
                    .whenComplete((success, error) -> {
                        if (error != null || !Boolean.TRUE.equals(success)) {
                            log.error("Failed to persist stopped player state or delete Redis cache: playerId={}",
                                    playerId, error);
                        }
                    });
        } catch (RejectedExecutionException exception) {
            log.error("Unable to schedule stopped player persistence: playerId={}", playerId, exception);
        }
    }

    /** 判断当前是否存在未完成的 Redis 脏数据刷写。 */
    boolean isRedisFlushPending() {
        return redisFlushPending;
    }

    /** 处理脏数据刷新定时器消息。 */
    private Behavior<PlayerMessage> onFlushDirty(FlushDirty message) {
        refreshFlushTimerIfNeeded();
        PlayerLifecycle currentLifecycle = lifecycle();
        PlayerPO state = currentLifecycle.state();
        if (!currentLifecycle.isOnline() || redisFlushPending || !state.hasDirtyFields()) {
            return org.apache.pekko.actor.typed.javadsl.Behaviors.same();
        }
        Map<String, String> dirtySnapshot = immutableCopy(state.toHash(state.getDirtyFields()));
        Map<String, String> stateSnapshot = immutableCopy(state.toHash());
        flushDirty(dirtySnapshot, stateSnapshot, state.getVersionValue());
        return org.apache.pekko.actor.typed.javadsl.Behaviors.same();
    }

    /** 处理 Redis 脏数据刷写结果，并在数据库同步成功后清理脏字段。 */
    private Behavior<PlayerMessage> onRedisFlushResult(RedisFlushResult result) {
        if (completeFlush(result)) {
            lifecycle().state().clearDirtyMatching(result.dirtyFields());
        }
        return org.apache.pekko.actor.typed.javadsl.Behaviors.same();
    }

    /** 启动一次 Redis 脏字段刷写。 */
    private void flushDirty(
            Map<String, String> dirtySnapshot,
            Map<String, String> stateSnapshot,
            long version
    ) {
        redisFlushPending = true;
        RedisStore store = redisStore();
        ExecutorService executor = LogicContext.getRedisIoExecutor();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> store.flushDirty(playerId, dirtySnapshot), executor);
        } catch (RejectedExecutionException exception) {
            services.context().getSelf().tell(new RedisFlushResult(
                    dirtySnapshot, stateSnapshot, version, false, exception));
            return;
        }
        services.context().pipeToSelf(future,
                (success, error) -> new RedisFlushResult(
                        dirtySnapshot, stateSnapshot, version, success, error));
    }

    /** 完成 Redis 刷写并提交数据库同步。 */
    boolean completeFlush(RedisFlushResult result) {
        redisFlushPending = false;
        if (result.error() != null || !Boolean.TRUE.equals(result.success())) {
            log.warn("Dirty flush error: playerId={}, fields={}, error={}",
                    playerId, result.dirtyFields().keySet(), result.error());
            return false;
        }
        if (!submitDatabase(result.stateSnapshot(), result.version())) {
            log.warn("Dirty flush retained because DB sync queue is full: playerId={}, fields={}",
                    playerId, result.dirtyFields().keySet());
            return false;
        }
        return true;
    }

    /** 根据最新配置刷新脏数据定时器。 */
    private void refreshFlushTimerIfNeeded() {
        long currentIntervalMs = LogicContext.getSyncIntervalMs();
        if (currentIntervalMs == scheduledSyncIntervalMs) {
            return;
        }
        scheduledSyncIntervalMs = currentIntervalMs;
        timers.startTimerWithFixedDelay(
                FLUSH_KEY,
                new FlushDirty(),
                Duration.ofMillis(scheduledSyncIntervalMs),
                Duration.ofMillis(scheduledSyncIntervalMs));
    }

    /** 提交完整玩家快照到数据库同步框架。 */
    private boolean submitDatabase(Map<String, String> snapshot, long version) {
        return LogicContext.getDbSyncEngine().submit(Long.parseLong(playerId), snapshot, version);
    }

    /** 延迟获取 Redis 状态访问对象。 */
    private RedisStore redisStore() {
        if (redisStore == null) {
            redisStore = LogicContext.getRedisStore();
        }
        return redisStore;
    }

    /** 返回已绑定的生命周期组件，防止组件装配遗漏导致消息被静默处理。 */
    private PlayerLifecycle lifecycle() {
        if (lifecycle == null) {
            throw new IllegalStateException("Player lifecycle is not bound");
        }
        return lifecycle;
    }

    /** 创建不可变状态快照，避免异步任务读取到后续变更。 */
    private static Map<String, String> immutableCopy(Map<String, String> source) {
        return Map.copyOf(source);
    }
}
