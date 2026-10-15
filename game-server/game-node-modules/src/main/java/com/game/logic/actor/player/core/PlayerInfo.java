package com.game.logic.actor.player.core;

import com.game.logic.actor.core.ActorServerSync;
import com.game.logic.actor.player.db.PlayerMapper;
import com.game.logic.actor.player.db.PlayerPO;
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
import org.apache.pekko.japi.function.Function;

import java.sql.Timestamp;
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
    /** 是否已在销毁流程中完成落库，避免 PostStop 重复写库。 */
    private boolean persistedOnDestroy;

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
                .onMessage(LoginRequest.class, msg ->
                        services.contextualize(msg, persistAfter(this::onLoginMessage)))
                .onMessage(LogoutRequest.class, msg ->
                        services.contextualize(msg, persistAfter(this::onLogoutMessage)))
                .onMessage(GmKickPlayerCommand.class, msg ->
                        services.contextualize(msg, persistAfter(this::onGmKickPlayer)))
                .onSignal(PostStop.class, signal -> onPostStop());
    }

    /**
     * 包装消息处理器：一条消息处理完成后，把本次累积的脏字段一次性 write-through 到 Redis。
     * <p>玩家不在线（登出、踢人已置离线）时跳过，最终状态由 {@link #beginDestroy()} 直接落库。</p>
     */
    private <T extends PlayerMessage> Function<T, Behavior<PlayerMessage>> persistAfter(
            Function<T, Behavior<PlayerMessage>> handler) {
        return message -> {
            Behavior<PlayerMessage> behavior = handler.apply(message);
            if (online && data.hasDirtyFields()) {
                LogicContext.getRedisStore().saveDirty(data, services.entityId());
            }
            return behavior;
        };
    }

    /**
     * 处理玩家登录请求，重复登录直接忽略。
     * <p>在线期间只写 Redis 缓存，不落库；玩家状态在登出销毁时统一落库。</p>
     */
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

    /** 处理玩家登出请求：落库后回复并停止 Actor。 */
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

    /**
     * 开始玩家销毁流程：把最终状态直接落数据库（不再写缓存），DB 确认后才清理 Redis 缓存；
     * 未确认则不清理，保留在线期间由消息处理 flush 写入的副本兜底。
     */
    private void beginDestroy() {
        if (!online) {
            return;
        }
        online = false;
        long now = System.currentTimeMillis();
        data.setProperty(PlayerPO::setOnline, 0)
                .setProperty(PlayerPO::setUpdatedAt, new Timestamp(now));
        persistedOnDestroy = true;
        stateSync.persistAsync(() -> LogicContext.getRedisStore().deletePlayerCache(services.entityId()));
    }

    /** Actor 停止时为尚未落库的玩家状态安排补偿持久化。 */
    private Behavior<PlayerMessage> onPostStop() {
        if (!persistedOnDestroy) {
            stateSync.persistAllStopped();
        }
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

    /** 更新在线状态、登录时间、更新时间和版本号。 */
    private void markOnlineFields() {
        long now = System.currentTimeMillis();
        data.setProperty(PlayerPO::setOnline, 1)
                .setProperty(PlayerPO::setLastLoginAt, new Timestamp(now))
                .setProperty(PlayerPO::setUpdatedAt, new Timestamp(now))
                .setProperty(PlayerPO::setVersion, Math.toIntExact(data.getVersionValue() + 1L));
    }
}
