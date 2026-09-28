package com.game.logic.actor.player.core;

import com.game.logic.actor.player.message.CacheLoadResult;
import com.game.logic.actor.player.message.DatabaseLoadResult;
import com.game.logic.actor.player.message.DestroyResult;
import com.game.logic.actor.player.message.InitialRedisWriteResult;
import com.game.logic.actor.player.message.LoadTimeout;
import com.game.logic.context.LogicContext;
import com.game.logic.dbsync.PlayerPO;
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
import com.xs.kernel.actor.ActorServices;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.StashBuffer;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.util.Map;

/** 注册并处理玩家业务消息及玩家在线、加载、销毁生命周期。 */
@Slf4j
public final class PlayerLifecycle {

    /** 登录加载超时定时器的唯一键。 */
    private static final Object LOAD_TIMEOUT_KEY = new Object();
    /** 玩家登录加载流程允许的最长耗时，单位为毫秒。 */
    private static final long LOAD_TIMEOUT_MS = 3000L;

    /** Actor 基础能力和 RPC 发送桥接。 */
    private final ActorServices<PlayerMessage> services;
    /** 登录加载期间暂存后续消息的缓冲区。 */
    private final StashBuffer<PlayerMessage> stash;
    /** 登录超时定时器。 */
    private final TimerScheduler<PlayerMessage> timers;
    /** 玩家状态读写同步组件。 */
    private final PlayerStateSync stateSync;
    /** 玩家业务状态。 */
    private final PlayerPO state = new PlayerPO();
    /** 玩家当前场景关系。 */
    private final PlayerSceneTracker sceneTracker = new PlayerSceneTracker();

    /** 当前生命周期阶段。 */
    private Phase phase = Phase.NEW;
    /** 加载期间收到的玩家名称。 */
    private String pendingPlayerName;
    /** 当前玩家绑定的频道 ID。 */
    private String pendingChannelId;
    /** 登录流程开始时间，用于记录耗时。 */
    private long loginStartedNanos;
    /** 等待销毁持久化完成后回复的登出请求。 */
    private LogoutRequest pendingLogout;

    /** 仅用于生命周期状态单元测试的无依赖构造器。 */
    PlayerLifecycle() {
        this.services = null;
        this.stash = null;
        this.timers = null;
        this.stateSync = null;
    }

    /**
     * 创建生产环境使用的玩家生命周期组件。
     *
     * @param services Actor 基础能力桥接
     * @param stash 登录加载期间的消息暂存区
     * @param timers Actor 定时器调度器
     * @param stateSync 玩家状态同步组件
     */
    PlayerLifecycle(
            ActorServices<PlayerMessage> services,
            StashBuffer<PlayerMessage> stash,
            TimerScheduler<PlayerMessage> timers,
            PlayerStateSync stateSync
    ) {
        this.services = services;
        this.stash = stash;
        this.timers = timers;
        this.stateSync = stateSync;
    }

    /**
     * 注册玩家业务消息、生命周期结果消息和 Actor 停止信号。
     *
     * @param builder 玩家 Actor 的消息接收构建器
     */
    void registerHandlers(ReceiveBuilder<PlayerMessage> builder) {
        builder
                .onMessage(PlayerEnterCommand.class, services.contextual(this::onPlayerEnterMessage))
                .onMessage(LogoutRequest.class, services.contextual(this::onLogoutMessage))
                .onMessage(GmKickPlayerCommand.class, services.contextual(this::onGmKickPlayer))
                .onMessage(PlayerSceneChanged.class, services.contextual(this::onSceneChanged))
                .onMessage(LoadTimeout.class, this::onLoadTimeout)
                .onMessage(CacheLoadResult.class, this::onCacheLoadResult)
                .onMessage(DatabaseLoadResult.class, this::onDatabaseLoadResult)
                .onMessage(InitialRedisWriteResult.class, this::onInitialRedisWriteResult)
                .onMessage(DestroyResult.class, this::onDestroyResult)
                .onSignal(PostStop.class, signal -> onPostStop());
    }

    /** 处理玩家进入消息，并按生命周期决定忽略、暂存或执行登录。 */
    private Behavior<PlayerMessage> onPlayerEnterMessage(PlayerEnterCommand command) {
        if (isStopping()) {
            log.info("Player enter ignored while entity is stopping: playerId={}", services.entityId());
            return Behaviors.same();
        }
        if (isLoading()) {
            stash.stash(command);
            return Behaviors.same();
        }
        onPlayerEnter(command);
        return Behaviors.same();
    }

    /** 处理玩家登出请求，在线状态下等待销毁持久化完成后回复。 */
    private Behavior<PlayerMessage> onLogoutMessage(LogoutRequest request) {
        if (isLoading()) {
            stash.stash(request);
            return Behaviors.same();
        }
        if (isDestroying()) {
            log.info("Duplicate logout ignored while player state is being persisted: playerId={}",
                    services.entityId());
            return Behaviors.same();
        }
        if (isOnline()) {
            log.info("PlayerActor logout: playerId={}", services.entityId());
            pendingLogout = request;
            beginDestroy();
            return Behaviors.same();
        }
        sendResponse(onLogout(request), request);
        return Behaviors.stopped();
    }

    /** 处理 GM 踢人指令，通知网关断开频道后进入销毁流程。 */
    private Behavior<PlayerMessage> onGmKickPlayer(GmKickPlayerCommand command) {
        if (!isOnline()) {
            log.info("GM kick ignored because player is not online: requestId={}, playerId={}",
                    command.requestId(), services.entityId());
            return Behaviors.same();
        }
        if (pendingChannelId != null && !pendingChannelId.isBlank()) {
            services.sendRpc(GatewayControlMessage.class, new DisconnectChannelCommand(
                    command.requestId(),
                    Long.parseLong(services.entityId()),
                    command.reason()), pendingChannelId);
        }
        log.info("GM kick executed: requestId={}, playerId={}, operator={}",
                command.requestId(), services.entityId(), command.operator());
        beginDestroy();
        return Behaviors.same();
    }

    /** 处理玩家场景成员关系变化，并忽略离线或过期的场景消息。 */
    private Behavior<PlayerMessage> onSceneChanged(PlayerSceneChanged command) {
        if (!isOnline()) {
            log.info("Scene membership ignored because player is offline: playerId={}", services.entityId());
            return Behaviors.same();
        }
        boolean changed = command.joined()
                ? sceneTracker.join(command.sceneId())
                : sceneTracker.leave(command.sceneId());
        if (changed) {
            log.info("Player scene changed: playerId={}, sceneId={}",
                    services.entityId(), sceneTracker.currentSceneId());
        } else {
            log.info("Ignoring stale scene leave: playerId={}, sceneId={}, currentSceneId={}",
                    services.entityId(), command.sceneId(), sceneTracker.currentSceneId());
        }
        return Behaviors.same();
    }

    /** 执行玩家进入行为：首次进入加载状态，已在线玩家刷新登录状态。 */
    private void onPlayerEnter(PlayerEnterCommand command) {
        if (beginLoading(command)) {
            timers.startSingleTimer(LOAD_TIMEOUT_KEY, new LoadTimeout(), Duration.ofMillis(LOAD_TIMEOUT_MS));
            stateSync.loadFromCache(command.playerId());
            return;
        }
        if (isOnline()) {
            applyEnter(command);
            return;
        }
        log.warn("Ignoring PlayerEnter while player lifecycle is not ready: playerId={}", services.entityId());
    }

    /** 处理 Redis 缓存读取结果；缓存未命中时转数据库读取。 */
    private Behavior<PlayerMessage> onCacheLoadResult(CacheLoadResult result) {
        if (!isLoading()) {
            return Behaviors.same();
        }
        if (result.error() == null && result.fields() != null && !result.fields().isEmpty()) {
            writeInitialState(result.playerId(), result.fields());
            return Behaviors.same();
        }
        if (result.error() != null) {
            log.warn("Failed to read player cache, requesting db load: playerId={}",
                    services.entityId(), result.error());
        }
        stateSync.loadFromDatabase(result.playerId());
        return Behaviors.same();
    }

    /** 处理数据库读取结果，成功后继续首次在线状态写入。 */
    private Behavior<PlayerMessage> onDatabaseLoadResult(DatabaseLoadResult result) {
        if (!isLoading()) {
            return Behaviors.same();
        }
        if (result.error() != null || result.fields() == null || result.fields().isEmpty()) {
            log.error("Player load failed: playerId={}", services.entityId(), result.error());
            timers.cancel(LOAD_TIMEOUT_KEY);
            failLoading();
            stash.clear();
            return Behaviors.stopped();
        }
        writeInitialState(result.playerId(), result.fields());
        return Behaviors.same();
    }

    /** 准备在线状态并提交首次 Redis 写入。 */
    private void writeInitialState(Long playerId, Map<String, String> fields) {
        stateSync.writeInitialState(playerId, prepareOnlineState(playerId, fields));
    }

    /** 处理首次在线状态写入结果，成功后转为在线并释放暂存消息。 */
    private Behavior<PlayerMessage> onInitialRedisWriteResult(InitialRedisWriteResult result) {
        if (!isLoading()) {
            return Behaviors.same();
        }
        if (result.error() != null || !Boolean.TRUE.equals(result.success())) {
            log.error("Failed to write player state to Redis: playerId={}", services.entityId(), result.error());
            timers.cancel(LOAD_TIMEOUT_KEY);
            failLoading();
            stash.clear();
            return Behaviors.stopped();
        }
        timers.cancel(LOAD_TIMEOUT_KEY);
        state.clearDirty();
        markOnline();
        stateSync.startFlushTimer();
        log.info("PlayerActor loaded: playerId={}", services.entityId());
        sendLoginResponse(result.playerId(), state.getName(), pendingChannelId);
        LogicContext.getLogicMetrics().recordLoginLogicTotal(System.nanoTime() - loginStartedNanos);
        return stash.unstashAll(Behaviors.same());
    }

    /** 处理登录加载超时，清理暂存消息并停止 Actor。 */
    private Behavior<PlayerMessage> onLoadTimeout(LoadTimeout message) {
        if (isLoading()) {
            log.error("Player load timed out: playerId={}", services.entityId());
            failLoading();
            stash.clear();
            return Behaviors.stopped();
        }
        return Behaviors.same();
    }

    /** 开始玩家销毁流程，停止刷新定时器并持久化离线状态。 */
    private void beginDestroy() {
        if (!beginDestroyLifecycle()) {
            return;
        }
        stateSync.cancelFlushTimer();
        stateSync.destroy(immutableCopy(state.toHash()), state.getVersionValue());
    }

    /** 处理玩家销毁结果，完成登出响应后停止 Actor。 */
    private Behavior<PlayerMessage> onDestroyResult(DestroyResult result) {
        if (stateSync.completeDestroy(result)) {
            markDestroyed();
        }
        LogoutRequest request = pendingLogout;
        pendingLogout = null;
        if (request != null) {
            sendResponse(onLogout(request), request);
        }
        return Behaviors.stopped();
    }

    /** Actor 停止时，为仍需保存的玩家状态安排补偿持久化。 */
    private Behavior<PlayerMessage> onPostStop() {
        if (shouldPersistOnStop()) {
            log.info("PlayerActor stopping, scheduling state persistence: playerId={}", services.entityId());
            stateSync.persistStopped(immutableCopy(state.toHash()), state.getVersionValue());
        }
        return Behaviors.same();
    }

    /** 将登出请求映射为登出响应。 */
    private LogoutResponse onLogout(LogoutRequest request) {
        return new LogoutResponse(request.playerId(), request.sessionId());
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
    private void sendLoginResponse(Long playerId, String playerName, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            log.warn("Cannot send SC_Login without channelId: playerId={}", playerId);
            return;
        }
        LoginResponse response = new LoginResponse(0, String.valueOf(playerId), playerName, null);
        LogicContext.getLogicMetrics().setLoginS2cSendNanos(System.nanoTime());
        services.sendRpc(S2C.class, response, channelId);
    }

    /** 开始新的玩家加载流程。 */
    boolean beginLoading(PlayerEnterCommand command) {
        if (phase != Phase.NEW) {
            return false;
        }
        phase = Phase.LOADING;
        pendingPlayerName = command.playerName();
        pendingChannelId = command.channelId();
        loginStartedNanos = System.nanoTime();
        state.setProperty(PlayerPO::setId, command.playerId())
                .setProperty(PlayerPO::setName, command.playerName());
        return true;
    }

    /** 将读取到的玩家数据应用到运行时状态，并准备首次在线写入。 */
    Map<String, String> prepareOnlineState(Long playerId, Map<String, String> fields) {
        state.fromHash(fields);
        if (state.getId() == null) {
            state.setId(playerId);
        }
        if (state.getName() == null) {
            state.setName(pendingPlayerName);
        }
        markOnlineFields();
        return immutableCopy(state.toHash());
    }

    /** 标记首次在线状态已经成功写入同步层。 */
    void markOnline() {
        if (phase == Phase.LOADING) {
            phase = Phase.ONLINE;
        }
    }

    /** 加载失败后清理生命周期状态。 */
    void failLoading() {
        if (phase == Phase.LOADING) {
            phase = Phase.NEW;
        }
    }

    /** 处理已在线玩家再次进入时的状态更新。 */
    private void applyEnter(PlayerEnterCommand command) {
        String playerName = command.playerName();
        if (playerName != null && !playerName.equals(state.getName())) {
            state.setProperty(PlayerPO::setName, playerName);
        }
        markOnlineFields();
        state.saveRedis();
        log.info("PlayerActor enter: playerId={}, channelId={}", services.entityId(), command.channelId());
        sendLoginResponse(command.playerId(), state.getName(), command.channelId());
    }

    /** 开始销毁状态转换并标记玩家离线。 */
    boolean beginDestroyLifecycle() {
        if (!isOnline()) {
            return false;
        }
        phase = Phase.DESTROYING;
        long now = System.currentTimeMillis();
        state.setProperty(PlayerPO::setOnline, false)
                .setProperty(PlayerPO::setUpdatedAtMillis, now);
        return true;
    }

    /** 标记玩家已经完成销毁。 */
    void markDestroyed() {
        phase = Phase.DESTROYED;
    }

    /** 判断当前玩家是否正在加载。 */
    boolean isLoading() {
        return phase == Phase.LOADING;
    }

    /** 判断当前玩家是否在线。 */
    boolean isOnline() {
        return phase == Phase.ONLINE;
    }

    /** 判断当前玩家是否正在停止或已经停止。 */
    boolean isStopping() {
        return phase == Phase.DESTROYING || phase == Phase.DESTROYED;
    }

    /** 判断当前玩家是否正在销毁。 */
    boolean isDestroying() {
        return phase == Phase.DESTROYING;
    }

    /** 判断 Actor 停止时是否仍需补偿持久化。 */
    boolean shouldPersistOnStop() {
        return phase == Phase.ONLINE || phase == Phase.DESTROYING;
    }

    /** 更新在线状态、登录时间、更新时间和版本号。 */
    private void markOnlineFields() {
        long now = System.currentTimeMillis();
        state.setProperty(PlayerPO::setOnline, true)
                .setProperty(PlayerPO::setLastLoginAtMillis, now)
                .setProperty(PlayerPO::setUpdatedAtMillis, now)
                .setProperty(PlayerPO::setVersionValue, state.getVersionValue() + 1L);
    }

    /** 返回当前玩家业务状态，供状态同步组件创建和清理持久化快照。 */
    PlayerPO state() {
        return state;
    }

    /** 创建不可变状态快照，供异步同步使用。 */
    private static Map<String, String> immutableCopy(Map<String, String> source) {
        return Map.copyOf(source);
    }

    private enum Phase {
        NEW,
        LOADING,
        ONLINE,
        DESTROYING,
        DESTROYED
    }
}
