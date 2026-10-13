package com.game.logic.actor.player.core;

import com.game.logic.actor.core.ActorServerSync;
import com.game.logic.actor.player.db.PlayerMapper;
import com.game.logic.actor.player.db.PlayerPO;
import com.game.logic.actor.player.message.FlushDirty;
import com.game.logic.context.LogicContext;
import com.game.logic.redis.RedisStore;
import com.game.message.convert.GeneratedProtoConverters;
import com.game.message.actor.S2C;
import com.game.message.actor.gateway.GatewayControlMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.c2s.LoginRequest;
import com.game.message.c2s.LogoutRequest;
import com.game.message.management.DisconnectChannelCommand;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.routing.ChannelRoutable;
import com.game.message.s2c.LoginResponse;
import com.game.message.s2c.LogoutResponse;
import com.game.message.s2c.PlayerInfoData;
import com.xs.kernel.actor.ActorServices;
import com.xs.kernel.actor.SimpleActorServer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

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

        // 1. 先尝试从 Redis 缓存读取
        PlayerPO cached = redisStore.loadModel(String.valueOf(playerId), PlayerPO.class);
        if (cached != null) {
            log.debug("Player loaded from Redis cache: playerId={}", playerId);
            return cached;
        }

        // 2. 缓存未命中，从数据库读取或创建
        log.debug("Player cache miss, loading from database: playerId={}", playerId);
        Map<String, String> dbFields = LogicContext.getDbSyncEngineRegistry()
                .loadOrCreate("player", playerId);

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

    /** 处理脏数据刷新消息。 */
    private Behavior<PlayerMessage> onFlushDirty(FlushDirty message) {
        stateSync.onFlushDirty();
        return Behaviors.same();
    }

    /** 处理玩家登录请求，重复登录直接忽略。 */
    private Behavior<PlayerMessage> onLoginMessage(LoginRequest request) {
        if (online) {
            return Behaviors.same();
        }
        pendingChannelId = request.channelId();
        String playerName = request.playerName();
        if (playerName != null && !playerName.equals(data.getName())) {
            data.setProperty(PlayerPO::setName, playerName);
        }
        markOnlineFields();
        online = true;
        LogicContext.getRedisStore().saveRedis(data);
        sendLoginResponse(request.channelId());
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
            services.sendRpc(GatewayControlMessage.class, new DisconnectChannelCommand(
                    command.requestId(),
                    Long.parseLong(services.entityId()),
                    command.reason()), pendingChannelId);
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
        services.sendRpc(S2C.class, response, request.channelId());
    }

    /** 向玩家当前频道发送登录成功响应。 */
    private void sendLoginResponse(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            log.warn("Cannot send SC_Login without channelId: playerId={}", data.getId());
            return;
        }
        PlayerInfoData playerInfo = GeneratedProtoConverters.toPlayerInfoData(data);
        services.sendRpc(S2C.class, new LoginResponse(0, null, playerInfo), channelId);
    }

    /** 停止脏数据刷新定时器。 */
    private void cancelFlushTimer() {
        timers.cancel(FLUSH_KEY);
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
