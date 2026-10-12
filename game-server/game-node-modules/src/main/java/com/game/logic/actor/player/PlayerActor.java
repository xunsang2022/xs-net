package com.game.logic.actor.player;

import com.game.logic.actor.core.ActorServerSync;
import com.game.logic.actor.player.core.PlayerInfo;
import com.game.logic.actor.player.db.PlayerMapper;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.kernel.actor.RpcActor;
import com.xs.kernel.actor.node.ActorDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.StashBuffer;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

/** 创建玩家 Actor，并将消息注册和处理职责交给玩家生命周期与状态同步组件。 */
@Slf4j
@ActorDispatcher("pekko.actor.player-dispatcher")
public class PlayerActor extends RpcActor<PlayerMessage> {
    /** 登录加载期间最多暂存的玩家消息数量。 */
    private static final int STASH_CAPACITY = 512;

    /** 玩家行为和生命周期处理组件。 */
    private final PlayerInfo lifecycle;

    /**
     * 创建玩家 Actor。
     *
     * @param context Actor 运行上下文
     * @param entityId 玩家实体 ID
     * @param stash 登录加载期间使用的消息暂存区
     * @param timers Actor 定时器调度器
     */
    public PlayerActor(
            ActorContext<PlayerMessage> context,
            String entityId,
            StashBuffer<PlayerMessage> stash,
            TimerScheduler<PlayerMessage> timers
    ) {
        super(context, entityId);
        ActorServerSync stateSync = new ActorServerSync();
        this.lifecycle = new PlayerInfo(this.services, stateSync, timers);
    }

    /**
     * 创建带有 Stash 和 Timer 能力的玩家 Actor Behavior。
     *
     * @param entityId 玩家实体 ID
     * @return 玩家 Actor 的 Behavior
     */
    public static Behavior<PlayerMessage> create(String entityId) {
        return Behaviors.withStash(STASH_CAPACITY, stash ->
                Behaviors.withTimers(timers ->
                        Behaviors.setup(context -> new PlayerActor(context, entityId, stash, timers))));
    }

    /**
     * 让各功能组件自行注册它们负责的消息和信号处理器。
     *
     * @return 当前玩家 Actor 的消息接收定义
     */
    @Override
    public Receive<PlayerMessage> createReceive() {
        ReceiveBuilder<PlayerMessage> builder = newRpcReceiveBuilder();
        lifecycle.registerHandlers(builder);
        return builder.build();
    }
}
