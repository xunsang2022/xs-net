package com.game.logic.actor.player.core;

import com.game.logic.actor.core.ActorServerSync;
import com.game.logic.actor.player.db.PlayerMapper;
import com.game.logic.actor.player.db.PlayerPO;
import com.game.logic.actor.player.message.FlushDirty;
import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicMetrics;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.S2C;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.c2s.LoginRequest;
import com.game.message.c2s.LogoutRequest;
import com.game.message.management.DisconnectChannelCommand;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.routing.ChannelRoutable;
import com.game.message.s2c.LoginResponse;
import com.game.message.s2c.LogoutResponse;
import com.xs.kernel.actor.ActorServices;
import com.xs.kernel.actor.SimpleActorServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;

/** 注册并处理玩家业务消息及玩家在线、加载、销毁生命周期。 */
@Slf4j
public final class PlayerInfo extends SimpleActorServer<PlayerMessage, PlayerPO> {
    /** 玩家状态同步组件。 */
    private final ActorServerSync stateSync;
    /** 当前玩家是否在线。 */
    private boolean online;
    /** 当前玩家绑定的频道 ID。 */
    private String pendingChannelId;

    public PlayerInfo(
            ActorServices<PlayerMessage> services,
            ActorServerSync stateSync,
            TimerScheduler<PlayerMessage> timers
    ) {
        super(services, timers , Long.parseLong(services.entityId()));
        this.stateSync = stateSync;
        this.stateSync.addModel(PlayerMapper.PLAYER_ENTITY_TYPE, services.entityId(), this::getData);
    }

    @Override
    public PlayerPO loadingModel(Object modelId) {
        Long playerId = (Long) modelId;
        RedisStore redisStore = LogicContext.getRedisStore();
        LogicMetrics metrics = LogicContext.getLogicMetrics();

        // 1. 先尝试从 Redis 缓存读取，记录命中/未命中/失败耗时
        long redisStartNanos = System.nanoTime();
        PlayerPO cached;
        try {
            cached = redisStore.loadModel(String.valueOf(playerId), PlayerPO.class);
        } catch (RuntimeException exception) {
            if (metrics != null) {
                metrics.recordLoginRedisLoadFailure(System.nanoTime() - redisStartNanos);
            }
            throw exception;
        }
        if (cached != null) {
            if (metrics != null) {
                metrics.recordLoginRedisLoad(System.nanoTime() - redisStartNanos, true);
            }
            log.debug("Player loaded from Redis cache: playerId={}", playerId);
            return cached;
        }
        if (metrics != null) {
            metrics.recordLoginRedisLoad(System.nanoTime() - redisStartNanos, false);
        }

        // 2. 缓存未命中，从数据库读取或创建，记录 DB 加载耗时
        log.debug("Player cache miss, loading from database: playerId={}", playerId);
        long databaseStartNanos = System.nanoTime();
        Map<String, String> dbFields = LogicContext.getDbSyncEngineRegistry()
                .loadOrCreate("player", playerId);
        if (metrics != null) {
            metrics.recordLoginDatabaseLoad(System.nanoTime() - databaseStartNanos);
        }

        // 3. 创建新的 PlayerPO 并从数据库字段填充
        PlayerPO po = new PlayerPO();
        po.fromHash(dbFields);
        if (po.getId() == null) {
            po.setId(playerId);
        }

        // 4. 将数据库读取的数据写入 Redis 缓存
        redisStore.saveModel(po, String.valueOf(playerId));

        return po;
    }

    @Override
    public void registerHandlers(ReceiveBuilder<PlayerMessage> builder) {
        builder
                .onMessage(LoginRequest.class, msg -> services.contextualize(msg, this::onLoginMessage))
                .onMessage(LogoutRequest.class, msg -> services.contextualize(msg, this::onLogoutMessage))
                .onMessage(GmKickPlayerCommand.class, msg -> services.contextualize(msg, this::onGmKickPlayer))
                .onMessage(FlushDirty.class, this::onFlushDirty)
                .onSignal(PostStop.class, signal -> onPostStop());
    }

    /** 处理脏数据刷新消息：仅在线时刷写，并在处理完后按最新间隔重新排期。 */
    private Behavior<PlayerMessage> onFlushDirty(FlushDirty message) {
        if (!online) {
            return Behaviors.same();
        }
        stateSync.onFlushDirty();
        refreshFlushTimerIfNeeded();
        return Behaviors.same();
    }

    /** 处理玩家登录请求，重复登录直接忽略。 */
    private Behavior<PlayerMessage> onLoginMessage(LoginRequest request) {
        if (online) {
            return Behaviors.same();
        }
        long loginStartNanos = System.nanoTime();
        LogicMetrics metrics = LogicContext.getLogicMetrics();
        pendingChannelId = request.channelId();
        String playerName = request.playerName();
        if (playerName != null && !playerName.equals(data.getName())) {
            data.setProperty(PlayerPO::setName, playerName);
        }
        markOnlineFields();
        online = true;
        LogicContext.getRedisStore().saveRedis(data);
        // 登录成功后启动脏数据刷新周期调度（单次定时器，仅此一次）
        refreshFlushTimerIfNeeded();
        if (pendingChannelId == null || pendingChannelId.isBlank()) {
            log.warn("Cannot send SC_Login without channelId: playerId={}", data.getId());
            return Behaviors.same();
        }
        long sendStartNanos = System.nanoTime();
        services.sendRpc(pendingChannelId, new LoginResponse(0, null, data.toVo()));
        if (metrics != null) {
            metrics.recordLoginS2cSend(System.nanoTime() - sendStartNanos);
            metrics.recordLoginLogicTotal(System.nanoTime() - loginStartNanos);
        }
        return Behaviors.same();
    }

    /** 处理玩家登出请求，回复后停止 Actor。 */
    private Behavior<PlayerMessage> onLogoutMessage(LogoutRequest request) {
        if (online) {
            beginDestroy();
        }
        sendResponse(new LogoutResponse(request.playerId(), request.sessionId()), request);
        return Behaviors.stopped();
    }

    /** 处理 GM 踢人指令，通知网关断开频道后进入销毁流程。 */
    private Behavior<PlayerMessage> onGmKickPlayer(GmKickPlayerCommand command) {
        if (!online) {
            return Behaviors.same();
        }
        if (pendingChannelId != null && !pendingChannelId.isBlank()) {
            services.sendRpc(pendingChannelId, new DisconnectChannelCommand(
                    command.requestId(),
                    Long.parseLong(services.entityId()),
                    command.reason()));
        }
        beginDestroy();
        return Behaviors.stopped();
    }

    /** 开始玩家销毁流程，停止刷新定时器并持久化离线状态。 */
    private void beginDestroy() {
        if (!online) {
            return;
        }
        online = false;
        cancelFlushTimer();
        long now = System.currentTimeMillis();
        data.setProperty(PlayerPO::setOnline, 0)
                .setProperty(PlayerPO::setUpdatedAt, new Timestamp(now));
        stateSync.destroyAll();
        LogicContext.getRedisStore().deletePlayerCache(services.entityId());
    }

    /** Actor 停止时，为仍需保存的玩家状态安排补偿持久化。 */
    private Behavior<PlayerMessage> onPostStop() {
        stateSync.persistAllStopped();
        return Behaviors.same();
    }

    /** 向请求频道发送 RPC 响应，并校验频道和响应类型。 */
    private void sendResponse(Object response, ChannelRoutable request) {
        if (request.channelId() == null || request.channelId().isBlank()) {
            log.warn("Cannot route RPC response without authenticated channel: playerId={}", services.entityId());
            return;
        }
        if (!(response instanceof S2C)) {
            log.warn("Response is not an S2C message: playerId={}, type={}",
                    services.entityId(), response.getClass().getName());
            return;
        }
        services.sendRpc(request.channelId(), response);
    }

    /** 停止脏数据刷新定时器。 */
    private void cancelFlushTimer() {
        timers.cancel(FLUSH_KEY);
    }

    /**
     * 按当前同步间隔排期下一次脏数据刷新（单次定时器）。
     * <p>每次触发后重新读取 {@link LogicContext#getSyncIntervalMs()}，因此间隔支持热更新；
     * 仅在线时排期，销毁后不再续期（beginDestroy 已 cancel）。</p>
     */
    private void refreshFlushTimerIfNeeded() {
        if (!online) {
            return;
        }
        timers.startSingleTimer(FLUSH_KEY, new FlushDirty(), Duration.ofMillis(LogicContext.getSyncIntervalMs()));
    }

    /** 更新在线状态、登录时间、更新时间和版本号。 */
    private void markOnlineFields() {
        long now = System.currentTimeMillis();
        data.setProperty(PlayerPO::setOnline, 1)
                .setProperty(PlayerPO::setLastLoginAt, new Timestamp(now))
                .setProperty(PlayerPO::setUpdatedAt, new Timestamp(now))
                .setProperty(PlayerPO::setVersion, Math.toIntExact(data.getVersionValue() + 1L));
    }
}
