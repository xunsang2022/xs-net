package com.game.logic.actor;

import com.game.logic.context.LogicContext;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.player.PlayerState;
import com.game.logic.redis.RedisStore;
import com.game.logic.scene.PlayerSceneTracker;
import com.game.message.actor.S2C;
import com.game.message.actor.gateway.GatewayControlMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.c2s.LogoutRequest;
import com.game.message.c2s.PlayerEnterCommand;
import com.game.message.management.DisconnectChannelCommand;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.routing.ChannelRoutable;
import com.game.message.s2c.LoginResponse;
import com.game.message.s2c.LogoutResponse;
import com.game.message.scene.PlayerSceneChanged;
import com.xs.core.actor.RpcActor;
import com.xs.core.actor.node.ActorDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.StashBuffer;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@ActorDispatcher("pekko.actor.player-dispatcher")
public class PlayerActor extends RpcActor<PlayerMessage> {

    private static final Object LOAD_TIMEOUT_KEY = new Object();
    private static final Object FLUSH_KEY = new Object();
    private static final long LOAD_TIMEOUT_MS = 3000L;
    private static final int STASH_CAPACITY = 512;

    private final StashBuffer<PlayerMessage> stash;
    private final TimerScheduler<PlayerMessage> timers;
    private final PlayerState state = new PlayerState();
    private final PlayerSceneTracker sceneTracker = new PlayerSceneTracker();
    private long scheduledSyncIntervalMs;

    private RedisStore redisStore;
    private String pendingPlayerName;
    private String pendingChannelId;
    private boolean loaded;
    private boolean loadPending;
    private boolean redisFlushPending;
    private boolean destroyPending;
    private boolean destroyed;
    private long loginStartedNanos;
    private LogoutRequest pendingLogout;

    public PlayerActor(
            ActorContext<PlayerMessage> context,
            String entityId,
            StashBuffer<PlayerMessage> stash,
            TimerScheduler<PlayerMessage> timers
    ) {
        super(context, entityId);
        this.stash = stash;
        this.timers = timers;
        this.scheduledSyncIntervalMs = LogicContext.getSyncIntervalMs();
    }

    public static Behavior<PlayerMessage> create(String entityId) {
        return Behaviors.withStash(STASH_CAPACITY, stash ->
                Behaviors.withTimers(timers ->
                        Behaviors.setup(context -> new PlayerActor(context, entityId, stash, timers))));
    }

    @Override
    public Receive<PlayerMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(PlayerEnterCommand.class, contextual(this::onPlayerEnterMessage))
                .onMessage(LogoutRequest.class, contextual(this::onLogoutMessage))
                .onMessage(GmKickPlayerCommand.class, contextual(this::onGmKickPlayer))
                .onMessage(PlayerSceneChanged.class, contextual(this::onSceneChanged))
                .onMessage(FlushDirty.class, this::onFlushDirty)
                .onMessage(LoadTimeout.class, this::onLoadTimeout)
                .onMessage(CacheLoadResult.class, this::onCacheLoadResult)
                .onMessage(DatabaseLoadResult.class, this::onDatabaseLoadResult)
                .onMessage(InitialRedisWriteResult.class, this::onInitialRedisWriteResult)
                .onMessage(RedisFlushResult.class, this::onRedisFlushResult)
                .onMessage(DestroyResult.class, this::onDestroyResult)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<PlayerMessage> onPlayerEnterMessage(PlayerEnterCommand command) {
        if (destroyPending || destroyed) {
            log.info("Player enter ignored while entity is stopping: playerId={}", entityId());
            return this;
        }
        if (!loaded && loadPending) {
            stash.stash(command);
            return this;
        }
        onPlayerEnter(command);
        return this;
    }

    private Behavior<PlayerMessage> onLogoutMessage(LogoutRequest request) {
        if (!loaded && loadPending) {
            stash.stash(request);
            return this;
        }
        if (destroyPending) {
            log.info("Duplicate logout ignored while player state is being persisted: playerId={}", entityId());
            return this;
        }
        if (loaded && !destroyed) {
            log.info("PlayerActor logout: playerId={}", entityId());
            pendingLogout = request;
            beginDestroy();
            return this;
        }
        sendResponse(onLogout(request), request);
        return Behaviors.stopped();
    }

    private Behavior<PlayerMessage> onGmKickPlayer(GmKickPlayerCommand command) {
        if (!loaded || destroyed || destroyPending) {
            log.info("GM kick ignored because player is not online: requestId={}, playerId={}",
                    command.requestId(), entityId());
            return this;
        }
        if (pendingChannelId != null && !pendingChannelId.isBlank()) {
            sendRpc(GatewayControlMessage.class, new DisconnectChannelCommand(
                    command.requestId(),
                    Long.parseLong(entityId()),
                    command.reason()), pendingChannelId);
        }
        log.info("GM kick executed: requestId={}, playerId={}, operator={}",
                command.requestId(), entityId(), command.operator());
        beginDestroy();
        return this;
    }

    private Behavior<PlayerMessage> onSceneChanged(PlayerSceneChanged command) {
        if (!loaded || destroyed || destroyPending) {
            log.info("Scene membership ignored because player is offline: playerId={}", entityId());
            return this;
        }
        boolean changed = command.joined()
                ? sceneTracker.join(command.sceneId())
                : sceneTracker.leave(command.sceneId());
        if (changed) {
            log.info("Player scene changed: playerId={}, sceneId={}", entityId(), sceneTracker.currentSceneId());
        } else {
            log.info("Ignoring stale scene leave: playerId={}, sceneId={}, currentSceneId={}",
                    entityId(), command.sceneId(), sceneTracker.currentSceneId());
        }
        return this;
    }

    private void onPlayerEnter(PlayerEnterCommand command) {
        if (!loaded && !loadPending) {
            loadPending = true;
            pendingPlayerName = command.playerName();
            pendingChannelId = command.channelId();
            loginStartedNanos = System.nanoTime();
            state.setId(command.playerId());
            state.setName(command.playerName());
            timers.startSingleTimer(LOAD_TIMEOUT_KEY, new LoadTimeout(), Duration.ofMillis(LOAD_TIMEOUT_MS));
            beginCacheLoad(command.playerId());
            return;
        }

        if (loaded) {
            applyEnter(command);
            return;
        }

        log.warn("Ignoring PlayerEnter while loading because RPC replay is not available: playerId={}", entityId());
    }

    private void beginCacheLoad(Long playerId) {
        RedisStore store = redisStore();
        ExecutorService executor = redisIoExecutor();
        CompletableFuture<Map<String, String>> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    Map<String, String> cached = store.load(entityId());
                    LogicContext.getLogicMetrics().recordLoginRedisLoad(System.nanoTime() - started, !cached.isEmpty());
                    return cached;
                } catch (RuntimeException exception) {
                    LogicContext.getLogicMetrics().recordLoginRedisLoadFailure(System.nanoTime() - started);
                    throw exception;
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new CacheLoadResult(playerId, null, exception));
            return;
        }
        getContext().pipeToSelf(future, (fields, error) -> new CacheLoadResult(playerId, fields, error));
    }

    private Behavior<PlayerMessage> onCacheLoadResult(CacheLoadResult result) {
        if (!loadPending) {
            return this;
        }
        if (result.error == null && result.fields != null && !result.fields.isEmpty()) {
            beginInitialRedisWrite(result.playerId, result.fields);
            return this;
        }
        if (result.error != null) {
            log.warn("Failed to read player cache, requesting db load: playerId={}", entityId(), result.error);
        }
        beginDatabaseLoad(result.playerId);
        return this;
    }

    private void beginDatabaseLoad(Long playerId) {
        DBSyncEngine engine = dbsyncEngine();
        ExecutorService executor = dbIoExecutor();
        CompletableFuture<Map<String, String>> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    return engine.loadOrCreate(playerId);
                } finally {
                    LogicContext.getLogicMetrics().recordLoginDatabaseLoad(System.nanoTime() - started);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new DatabaseLoadResult(playerId, null, exception));
            return;
        }
        getContext().pipeToSelf(future, (fields, error) -> new DatabaseLoadResult(playerId, fields, error));
    }

    private Behavior<PlayerMessage> onDatabaseLoadResult(DatabaseLoadResult result) {
        if (!loadPending) {
            return this;
        }
        if (result.error != null || result.fields == null || result.fields.isEmpty()) {
            log.error("Player load failed: playerId={}", entityId(), result.error);
            timers.cancel(LOAD_TIMEOUT_KEY);
            loadPending = false;
            stash.clear();
            return Behaviors.stopped();
        }
        beginInitialRedisWrite(result.playerId, result.fields);
        return this;
    }

    private void beginInitialRedisWrite(Long playerId, Map<String, String> fields) {
        state.fromHash(fields);
        if (state.getId() == null) {
            state.setId(playerId);
        }
        if (state.getName() == null) {
            state.setName(pendingPlayerName);
        }

        long now = System.currentTimeMillis();
        state.setOnline(true);
        state.setLastLoginAt(now);
        state.setUpdatedAt(now);
        state.incrementVersion();
        state.markDirty(PlayerState.FIELD_ONLINE);
        state.markDirty(PlayerState.FIELD_LAST_LOGIN_AT);
        state.markDirty(PlayerState.FIELD_UPDATED_AT);
        state.markDirty(PlayerState.FIELD_VERSION);
        Map<String, String> snapshot = immutableCopy(state.toHash());
        RedisStore store = redisStore();
        ExecutorService executor = redisIoExecutor();

        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    return store.create(entityId(), snapshot);
                } finally {
                    LogicContext.getLogicMetrics().recordLoginRedisCreate(System.nanoTime() - started);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new InitialRedisWriteResult(playerId, false, exception));
            return;
        }
        getContext().pipeToSelf(future, (success, error) -> new InitialRedisWriteResult(playerId, success, error));
    }

    private Behavior<PlayerMessage> onInitialRedisWriteResult(InitialRedisWriteResult result) {
        if (!loadPending) {
            return this;
        }
        if (result.error != null || !Boolean.TRUE.equals(result.success)) {
            log.error("Failed to write player state to Redis: playerId={}", entityId(), result.error);
            timers.cancel(LOAD_TIMEOUT_KEY);
            loadPending = false;
            stash.clear();
            return Behaviors.stopped();
        }
        timers.cancel(LOAD_TIMEOUT_KEY);
        loadPending = false;
        state.clearDirty();
        loaded = true;
        startFlushTimer();
        log.info("PlayerActor loaded: playerId={}", entityId());
        sendLoginResponse(result.playerId, state.getName(), pendingChannelId);
        LogicContext.getLogicMetrics().recordLoginLogicTotal(System.nanoTime() - loginStartedNanos);
        return stash.unstashAll(this);
    }

    private Behavior<PlayerMessage> onLoadTimeout(LoadTimeout message) {
        if (!loaded && loadPending) {
            log.error("Player load timed out: playerId={}", entityId());
            loadPending = false;
            stash.clear();
            return Behaviors.stopped();
        }
        return this;
    }

    private Behavior<PlayerMessage> onFlushDirty(FlushDirty message) {
        refreshFlushTimerIfNeeded();
        if (!loaded || destroyed || destroyPending || redisFlushPending || !state.hasDirtyFields()) {
            return this;
        }
        Map<String, String> dirtySnapshot = immutableCopy(state.toHash(state.getDirtyFields()));
        Map<String, String> stateSnapshot = immutableCopy(state.toHash());
        long version = state.getVersion();
        redisFlushPending = true;
        RedisStore store = redisStore();
        ExecutorService executor = redisIoExecutor();

        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> store.flushDirty(entityId(), dirtySnapshot), executor);
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new RedisFlushResult(dirtySnapshot, stateSnapshot, version, false, exception));
            return this;
        }
        getContext().pipeToSelf(future,
                (success, error) -> new RedisFlushResult(dirtySnapshot, stateSnapshot, version, success, error));
        return this;
    }

    private Behavior<PlayerMessage> onRedisFlushResult(RedisFlushResult result) {
        redisFlushPending = false;
        if (result.error != null || !Boolean.TRUE.equals(result.success)) {
            log.warn("Dirty flush error: playerId={}, fields={}", entityId(), result.dirtyFields.keySet(), result.error);
            return this;
        }
        if (!dbsyncEngine().submit(Long.parseLong(entityId()), result.stateSnapshot, result.version)) {
            log.warn("Dirty flush retained because DB sync queue is full: playerId={}, fields={}",
                    entityId(), result.dirtyFields.keySet());
            return this;
        }
        state.clearDirtyMatching(result.dirtyFields);
        return this;
    }

    private void applyEnter(PlayerEnterCommand command) {
        String playerName = command.playerName();
        if (playerName != null && !playerName.equals(state.getName())) {
            state.setName(playerName);
            state.markDirty(PlayerState.FIELD_NAME);
        }
        long now = System.currentTimeMillis();
        state.setOnline(true);
        state.setLastLoginAt(now);
        state.setUpdatedAt(now);
        state.incrementVersion();
        state.markDirty(PlayerState.FIELD_ONLINE);
        state.markDirty(PlayerState.FIELD_LAST_LOGIN_AT);
        state.markDirty(PlayerState.FIELD_UPDATED_AT);
        state.markDirty(PlayerState.FIELD_VERSION);
        log.info("PlayerActor enter: playerId={}, channelId={}", entityId(), command.channelId());
        sendLoginResponse(command.playerId(), state.getName(), command.channelId());
    }

    private void beginDestroy() {
        destroyPending = true;
        timers.cancel(FLUSH_KEY);
        long now = System.currentTimeMillis();
        state.setOnline(false);
        state.setUpdatedAt(now);
        state.markDirty(PlayerState.FIELD_ONLINE);
        state.markDirty(PlayerState.FIELD_UPDATED_AT);
        Map<String, String> snapshot = immutableCopy(state.toHash());
        long version = state.getVersion();
        RedisStore store = redisStore();
        ExecutorService executor = redisIoExecutor();

        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> store.destroy(entityId(), snapshot), executor);
        } catch (RejectedExecutionException exception) {
            getContext().getSelf().tell(new DestroyResult(snapshot, version, false, exception));
            return;
        }
        getContext().pipeToSelf(future, (success, error) -> new DestroyResult(snapshot, version, success, error));
    }

    private Behavior<PlayerMessage> onDestroyResult(DestroyResult result) {
        destroyPending = false;
        if (result.error == null && Boolean.TRUE.equals(result.success)) {
            destroyed = true;
            if (!dbsyncEngine().submit(Long.parseLong(entityId()), result.stateSnapshot, result.version)) {
                log.error("Player destroy state was written to Redis but DB sync queue is full: playerId={}", entityId());
            }
        } else {
            log.error("Failed to destroy player state: playerId={}", entityId(), result.error);
        }
        if (pendingLogout != null) {
            sendResponse(onLogout(pendingLogout), pendingLogout);
            pendingLogout = null;
        }
        return Behaviors.stopped();
    }

    /**
     * Retained as the RPC response mapping; persistence completes asynchronously before it is sent.
     */
    private LogoutResponse onLogout(LogoutRequest request) {
        return new LogoutResponse(request.playerId(), request.sessionId());
    }

    private void sendResponse(Object response, ChannelRoutable request) {
        if (request.channelId() == null || request.channelId().isBlank()) {
            log.warn("Cannot route RPC response without authenticated channel: playerId={}", entityId());
            return;
        }
        if (!(response instanceof S2C)) {
            log.warn("Response is not an S2C message: playerId={}, type={}", entityId(), response.getClass().getName());
            return;
        }
        sendRpc(S2C.class, response, request.channelId());
    }

    private void sendLoginResponse(Long playerId, String playerName, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            log.warn("Cannot send SC_Login without channelId: playerId={}", playerId);
            return;
        }
        LoginResponse response = new LoginResponse(0, String.valueOf(playerId), playerName, null);
        LogicContext.getLogicMetrics().setLoginS2cSendNanos(System.nanoTime());
        sendRpc(S2C.class, response, channelId);
    }

    private Behavior<PlayerMessage> onPostStop() {
        if (loaded && !destroyed) {
            log.info("PlayerActor stopping, scheduling state persistence: playerId={}", entityId());
            Map<String, String> snapshot = immutableCopy(state.toHash());
            long version = state.getVersion();
            RedisStore store = redisStore();
            ExecutorService executor = redisIoExecutor();
            DBSyncEngine engine = dbsyncEngine();
            String playerId = entityId();
            try {
                CompletableFuture.supplyAsync(
                                () -> store.destroy(playerId, snapshot), executor)
                        .whenComplete((success, error) -> persistStoppedPlayer(
                                engine, playerId, snapshot, version, success, error));
            } catch (RejectedExecutionException exception) {
                log.error("Unable to schedule stopped player persistence: playerId={}", entityId(), exception);
            }
        }
        return Behaviors.same();
    }

    private static void persistStoppedPlayer(
            DBSyncEngine engine,
            String playerId,
            Map<String, String> snapshot,
            long version,
            Boolean success,
            Throwable error
    ) {
        if (error != null || !Boolean.TRUE.equals(success)) {
            log.error("Failed to persist stopped player state: playerId={}", playerId, error);
            return;
        }
        if (!engine.submit(Long.parseLong(playerId), snapshot, version)) {
            log.error("Stopped player state rejected by DB sync queue: playerId={}", playerId);
        }
    }

    private void startFlushTimer() {
        if (!timers.isTimerActive(FLUSH_KEY)) {
            timers.startTimerWithFixedDelay(
                    FLUSH_KEY,
                    new FlushDirty(),
                    Duration.ofMillis(scheduledSyncIntervalMs),
                    Duration.ofMillis(scheduledSyncIntervalMs));
        }
    }

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

    private RedisStore redisStore() {
        if (redisStore == null) {
            redisStore = LogicContext.getRedisStore();
        }
        return redisStore;
    }

    private DBSyncEngine dbsyncEngine() {
        return LogicContext.getDbSyncEngine();
    }

    private ExecutorService redisIoExecutor() {
        return LogicContext.getRedisIoExecutor();
    }

    private ExecutorService dbIoExecutor() {
        return LogicContext.getDbIoExecutor();
    }

    private static Map<String, String> immutableCopy(Map<String, String> source) {
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    private static final class FlushDirty implements PlayerMessage {
    }

    private static final class LoadTimeout implements PlayerMessage {
    }

    private static final class CacheLoadResult implements PlayerMessage {
        private final Long playerId;
        private final Map<String, String> fields;
        private final Throwable error;

        private CacheLoadResult(Long playerId, Map<String, String> fields, Throwable error) {
            this.playerId = playerId;
            this.fields = fields;
            this.error = error;
        }
    }

    private static final class DatabaseLoadResult implements PlayerMessage {
        private final Long playerId;
        private final Map<String, String> fields;
        private final Throwable error;

        private DatabaseLoadResult(Long playerId, Map<String, String> fields, Throwable error) {
            this.playerId = playerId;
            this.fields = fields;
            this.error = error;
        }
    }

    private static final class InitialRedisWriteResult implements PlayerMessage {
        private final Long playerId;
        private final Boolean success;
        private final Throwable error;

        private InitialRedisWriteResult(Long playerId, Boolean success, Throwable error) {
            this.playerId = playerId;
            this.success = success;
            this.error = error;
        }
    }

    private static final class RedisFlushResult implements PlayerMessage {
        private final Map<String, String> dirtyFields;
        private final Map<String, String> stateSnapshot;
        private final long version;
        private final Boolean success;
        private final Throwable error;

        private RedisFlushResult(
                Map<String, String> dirtyFields,
                Map<String, String> stateSnapshot,
                long version,
                Boolean success,
                Throwable error
        ) {
            this.dirtyFields = dirtyFields;
            this.stateSnapshot = stateSnapshot;
            this.version = version;
            this.success = success;
            this.error = error;
        }
    }

    private static final class DestroyResult implements PlayerMessage {
        private final Map<String, String> stateSnapshot;
        private final long version;
        private final Boolean success;
        private final Throwable error;

        private DestroyResult(Map<String, String> stateSnapshot, long version, Boolean success, Throwable error) {
            this.stateSnapshot = stateSnapshot;
            this.version = version;
            this.success = success;
            this.error = error;
        }
    }
}
