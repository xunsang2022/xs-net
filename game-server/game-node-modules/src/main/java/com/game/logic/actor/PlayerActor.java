package com.game.logic.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.StashBuffer;
import akka.actor.typed.javadsl.TimerScheduler;
import com.game.message.c2s.LogoutRequest;
import com.game.message.s2c.LogoutResponse;
import com.game.message.c2s.PlayerEnterCommand;
import com.game.message.s2c.LoginResponse;
import com.game.logic.context.LogicContext;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.player.PlayerState;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.S2C;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.core.actor.RpcActor;
import com.game.message.routing.ChannelRoutable;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class PlayerActor extends RpcActor<PlayerMessage> {

    private static final Object LOAD_TIMEOUT_KEY = new Object();
    private static final Object FLUSH_KEY = new Object();
    private static final long LOAD_TIMEOUT_MS = 3000L;
    private static final int STASH_CAPACITY = 512;

    private final StashBuffer<PlayerMessage> stash;
    private final TimerScheduler<PlayerMessage> timers;
    private final PlayerState state = new PlayerState();
    private final long syncIntervalMs;

    private RedisStore redisStore;
    private String pendingPlayerName;
    private String pendingChannelId;
    private boolean loaded;
    private boolean loadPending;
    private boolean destroyed;

    public PlayerActor(
            ActorContext<PlayerMessage> context,
            String entityId,
            StashBuffer<PlayerMessage> stash,
            TimerScheduler<PlayerMessage> timers
    ) {
        super(context, entityId);
        this.stash = stash;
        this.timers = timers;
        this.syncIntervalMs = LogicContext.getSyncIntervalMs();
    }

    public static Behavior<PlayerMessage> create(String entityId) {
        return Behaviors.withStash(STASH_CAPACITY, stash ->
                Behaviors.withTimers(timers ->
                        Behaviors.setup(context -> new PlayerActor(context, entityId, stash, timers))));
    }

    @Override
    public Receive<PlayerMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(PlayerEnterCommand.class, this::onPlayerEnterMessage)
                .onMessage(LogoutRequest.class, this::onLogoutMessage)
                .onMessage(FlushDirty.class, this::onFlushDirty)
                .onMessage(LoadTimeout.class, this::onLoadTimeout)
                .onMessage(LoadResult.class, this::onLoadResult)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<PlayerMessage> onPlayerEnterMessage(PlayerEnterCommand command) {
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
        LogoutResponse response = onLogout(request);
        sendResponse(response, request);
        return Behaviors.stopped();
    }

    private void onPlayerEnter(PlayerEnterCommand command) {
        if (!loaded && !loadPending) {
            loadPending = true;
            pendingPlayerName = command.playerName();
            pendingChannelId = command.channelId();
            state.setId(command.playerId());
            state.setName(command.playerName());

            try {
                Map<String, String> cached = redisStore().load(entityId());
                if (!cached.isEmpty()) {
                    finishLoad(command.playerId(), cached);
                    return;
                }
            } catch (Exception exception) {
                log.warn("Failed to read player cache, requesting db load: playerId={}", entityId(), exception);
            }

            timers.startSingleTimer(LOAD_TIMEOUT_KEY, new LoadTimeout(), Duration.ofMillis(LOAD_TIMEOUT_MS));
            CompletableFuture<Map<String, String>> future = CompletableFuture.supplyAsync(
                    () -> dbsyncEngine().loadOrCreate(command.playerId())
            );
            getContext().pipeToSelf(future, (fields, error) -> new LoadResult(fields, error));
            return;
        }

        if (loaded) {
            applyEnter(command);
            return;
        }

        log.warn("Ignoring PlayerEnter while loading because RPC replay is not available: playerId={}", entityId());
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

    private Behavior<PlayerMessage> finishLoad(Long playerId, Map<String, String> fields) {
        timers.cancel(LOAD_TIMEOUT_KEY);
        loadPending = false;
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

        try {
            // create 已把全量 state 写入 Redis（HPUTALL + 进 online 集合），无需再 flushDirty，
            // 直接清脏，避免登录热路径上的一次冗余 Redis 往返。
            redisStore().create(entityId(), state);
            state.clearDirty();
        } catch (Exception exception) {
            log.error("Failed to write player state to Redis: playerId={}", entityId(), exception);
            stash.clear();
            return Behaviors.stopped();
        }

        loaded = true;
        startFlushTimer();
        log.info("PlayerActor loaded: playerId={}", entityId());
        sendLoginResponse(playerId, state.getName(), pendingChannelId);
        return stash.unstashAll(this);
    }

    private Behavior<PlayerMessage> onLoadResult(LoadResult result) {
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
        return finishLoad(Long.parseLong(entityId()), result.fields);
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
        if (!loaded || !state.hasDirtyFields()) {
            return this;
        }
        try {
            if (redisStore().flushDirty(entityId(), state)) {
                dbsyncEngine().submit(Long.parseLong(entityId()), state.toHash(), state.getVersion());
                state.clearDirty();
            } else {
                log.warn("Dirty flush failed: playerId={}, fields={}", entityId(), state.getDirtyFields());
            }
        } catch (Exception exception) {
            log.warn("Dirty flush error: playerId={}", entityId(), exception);
        }
        return this;
    }

    private LogoutResponse onLogout(LogoutRequest request) {
        log.info("PlayerActor logout: playerId={}", entityId());
        if (loaded && !destroyed) {
            destroyState();
        }
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

    /** 进服完成后向客户端回 SC_Login（登录成功回执）。 */
    private void sendLoginResponse(Long playerId, String playerName, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            log.warn("Cannot send SC_Login without channelId: playerId={}", playerId);
            return;
        }
        LoginResponse response = new LoginResponse(0, String.valueOf(playerId), playerName, null);
        sendRpc(S2C.class, response, channelId);
    }

    private void destroyState() {
        long now = System.currentTimeMillis();
        state.setOnline(false);
        state.setUpdatedAt(now);
        state.markDirty(PlayerState.FIELD_ONLINE);
        state.markDirty(PlayerState.FIELD_UPDATED_AT);
        try {
            destroyed = redisStore().destroy(entityId(), state);
            if (destroyed) {
                dbsyncEngine().submit(Long.parseLong(entityId()), state.toHash(), state.getVersion());
            }
        } catch (Exception exception) {
            destroyed = false;
            log.error("Failed to destroy player state: playerId={}", entityId(), exception);
        }
    }

    private Behavior<PlayerMessage> onPostStop() {
        if (loaded && !destroyed) {
            log.info("PlayerActor stopping, flushing state: playerId={}", entityId());
            destroyState();
        }
        return Behaviors.same();
    }

    private void startFlushTimer() {
        if (!timers.isTimerActive(FLUSH_KEY)) {
            timers.startTimerWithFixedDelay(
                    FLUSH_KEY,
                    new FlushDirty(),
                    Duration.ofMillis(syncIntervalMs),
                    Duration.ofMillis(syncIntervalMs)
            );
        }
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

    private static final class FlushDirty implements PlayerMessage {
    }

    private static final class LoadTimeout implements PlayerMessage {
    }

    private static final class LoadResult implements PlayerMessage {
        private final Map<String, String> fields;
        private final Throwable error;

        private LoadResult(Map<String, String> fields, Throwable error) {
            this.fields = fields;
            this.error = error;
        }
    }
}
