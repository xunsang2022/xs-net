package com.game.logic.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.StashBuffer;
import akka.actor.typed.javadsl.TimerScheduler;
import com.game.logic.context.LogicContext;
import com.game.logic.player.PlayerState;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.S2C;
import com.game.message.actor.db.DbSyncCmd;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.rpc.GameRpcEnvelope;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.CS_Logout;
import com.game.protocol.pb.DbSyncLoadMessage;
import com.game.protocol.pb.DbSyncLoadResultMessage;
import com.game.protocol.pb.PlayerEnter;
import com.game.protocol.pb.SC_Logout;
import com.xs.core.akka.AkkaKit;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;

/**
 * Cluster sharded player entity on the logic node. State lives in Redis and is
 * written back to PostgreSQL by the db-sync service.
 */
@Slf4j
public class PlayerActor extends AbstractBehavior<PlayerMessage> {

    private static final Object LOAD_TIMEOUT_KEY = new Object();
    private static final Object FLUSH_KEY = new Object();
    private static final long LOAD_TIMEOUT_MS = 3000L;
    private static final int STASH_CAPACITY = 64;

    private final String entityId;
    private final StashBuffer<PlayerMessage> stash;
    private final TimerScheduler<PlayerMessage> timers;
    private final PlayerState state = new PlayerState();
    private final long syncIntervalMs;

    private RedisStore redisStore;
    private String pendingPlayerName;
    private boolean loaded;
    private boolean loadPending;
    private boolean destroyed;

    public PlayerActor(
            ActorContext<PlayerMessage> context,
            String entityId,
            StashBuffer<PlayerMessage> stash,
            TimerScheduler<PlayerMessage> timers
    ) {
        super(context);
        this.entityId = entityId;
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
                .onMessage(GameRpcEnvelope.class, this::onRpc)
                .onMessage(FlushDirty.class, this::onFlushDirty)
                .onMessage(LoadTimeout.class, this::onLoadTimeout)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<PlayerMessage> onRpc(GameRpcEnvelope envelope) {
        if (envelope.payload() instanceof PlayerEnter enter) {
            return onPlayerEnter(envelope, enter);
        }
        if (envelope.payload() instanceof CS_Logout logout) {
            return onLogout(logout);
        }
        if (envelope.payload() instanceof DbSyncLoadResultMessage result) {
            return onLoadResult(result);
        }
        log.warn("Unhandled rpc payload: {}", envelope.payload().getClass().getName());
        return this;
    }

    private Behavior<PlayerMessage> onPlayerEnter(GameRpcEnvelope envelope, PlayerEnter msg) {
        if (!loaded && !loadPending) {
            loadPending = true;
            pendingPlayerName = msg.hasPlayerName() ? msg.getPlayerName() : null;
            state.setId(msg.getPlayerId());
            state.setName(msg.getPlayerName());

            try {
                Map<String, String> cached = redisStore().load(entityId);
                if (!cached.isEmpty()) {
                    return finishLoad(msg.getPlayerId(), cached);
                }
            } catch (Exception e) {
                log.warn("Failed to read player cache, requesting db-sync load: playerId={}", entityId, e);
            }

            timers.startSingleTimer(LOAD_TIMEOUT_KEY, new LoadTimeout(), Duration.ofMillis(LOAD_TIMEOUT_MS));
            AkkaKit.tell(
                    DbSyncCmd.class,
                    GameRpcMessages.wrap(DbSyncLoadMessage.newBuilder().setPlayerId(msg.getPlayerId()).build()),
                    entityId
            );
            return this;
        }

        if (loaded) {
            applyEnter(msg);
            return this;
        }

        stash.stash(envelope);
        return this;
    }

    private void applyEnter(PlayerEnter msg) {
        String playerName = msg.hasPlayerName() ? msg.getPlayerName() : null;
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
        log.info("PlayerActor enter: playerId={}, channelId={}", entityId, msg.getChannelId());
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
            redisStore().create(entityId, state);
            if (state.hasDirtyFields() && redisStore().flushDirty(entityId, state)) {
                state.clearDirty();
            }
        } catch (Exception e) {
            log.error("Failed to write player state to Redis: playerId={}", entityId, e);
            stash.clear();
            return Behaviors.stopped();
        }

        loaded = true;
        startFlushTimer();
        log.info("PlayerActor loaded: playerId={}", entityId);
        return stash.unstashAll(this);
    }

    private Behavior<PlayerMessage> onLoadResult(DbSyncLoadResultMessage msg) {
        if (!msg.hasPlayerId() || !loadPending || !String.valueOf(msg.getPlayerId()).equals(entityId)) {
            return this;
        }
        if (!msg.getSuccess() || msg.getFieldsMap() == null || msg.getFieldsMap().isEmpty()) {
            log.error("Player load failed: playerId={}", entityId);
            timers.cancel(LOAD_TIMEOUT_KEY);
            loadPending = false;
            stash.clear();
            return Behaviors.stopped();
        }
        return finishLoad(msg.getPlayerId(), msg.getFieldsMap());
    }

    private Behavior<PlayerMessage> onLoadTimeout(LoadTimeout msg) {
        if (!loaded && loadPending) {
            log.error("Player load timed out: playerId={}", entityId);
            loadPending = false;
            stash.clear();
            return Behaviors.stopped();
        }
        return this;
    }

    private Behavior<PlayerMessage> onFlushDirty(FlushDirty msg) {
        if (!loaded || !state.hasDirtyFields()) {
            return this;
        }
        try {
            if (redisStore().flushDirty(entityId, state)) {
                state.clearDirty();
            } else {
                log.warn("Dirty flush failed: playerId={}, fields={}", entityId, state.getDirtyFields());
            }
        } catch (Exception e) {
            log.warn("Dirty flush error: playerId={}", entityId, e);
        }
        return this;
    }

    private Behavior<PlayerMessage> onLogout(CS_Logout msg) {
        SC_Logout.Builder logout = SC_Logout.newBuilder();
        if (msg.hasPlayerId()) {
            logout.setPlayerId(msg.getPlayerId());
        }
        String sessionId = msg.hasSessionId() ? msg.getSessionId() : "";
        if (msg.hasSessionId()) {
            logout.setSessionId(msg.getSessionId());
        }
        AkkaKit.tell(S2C.class, GameRpcMessages.wrap(logout.build()), sessionId);
        log.info("PlayerActor logout: playerId={}", entityId);
        if (loaded && !destroyed) {
            destroyState();
        }
        return Behaviors.stopped();
    }

    private void destroyState() {
        long now = System.currentTimeMillis();
        state.setOnline(false);
        state.setUpdatedAt(now);
        state.markDirty(PlayerState.FIELD_ONLINE);
        state.markDirty(PlayerState.FIELD_UPDATED_AT);
        try {
            destroyed = redisStore().destroy(entityId, state);
        } catch (Exception e) {
            destroyed = false;
            log.error("Failed to destroy player state: playerId={}", entityId, e);
        }
    }

    private Behavior<PlayerMessage> onPostStop() {
        if (loaded && !destroyed) {
            log.info("PlayerActor stopping, flushing state: playerId={}", entityId);
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

    private static final class FlushDirty implements PlayerMessage {
    }

    private static final class LoadTimeout implements PlayerMessage {
    }
}
