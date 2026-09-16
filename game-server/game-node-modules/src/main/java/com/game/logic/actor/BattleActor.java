package com.game.logic.actor;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleEndCommand;
import com.game.message.battle.BattleInputCommand;
import com.game.message.battle.BattleJoinCommand;
import com.game.message.battle.BattleLeaveCommand;
import com.xs.core.actor.RpcActor;
import com.xs.core.actor.node.ActorDispatcher;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@ActorDispatcher("pekko.actor.battle-dispatcher")
public final class BattleActor extends RpcActor<BattleMessage> {

    private static final Object TICK_KEY = new Object();
    private static final Duration TICK_INTERVAL = Duration.ofNanos(1_000_000_000L / 60L);
    private static final int MAX_PLAYERS = 100;
    private static final long TICK_BUDGET_NANOS = 16_666_667L;
    private static final String METRIC_TICK_DURATION = "battle.tick.duration";
    private static final String METRIC_TICK_OVERRUN = "battle.tick.overrun";
    private static final String METRIC_INPUT_REJECTED = "battle.input.rejected";

    private final TimerScheduler<BattleMessage> timers;
    private final BattleState state;

    private BattleActor(
            ActorContext<BattleMessage> context,
            String battleId,
            TimerScheduler<BattleMessage> timers
    ) {
        super(context, battleId);
        this.timers = timers;
        this.state = new BattleState(battleId, MAX_PLAYERS);
    }

    public static Behavior<BattleMessage> create(String battleId) {
        return Behaviors.withTimers(timers ->
                Behaviors.setup(context -> new BattleActor(context, battleId, timers)));
    }

    @Override
    public Receive<BattleMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(BattleCreateCommand.class, contextual(this::onCreate))
                .onMessage(BattleJoinCommand.class, contextual(this::onJoin))
                .onMessage(BattleLeaveCommand.class, contextual(this::onLeave))
                .onMessage(BattleInputCommand.class, contextual(this::onInput))
                .onMessage(BattleEndCommand.class, contextual(this::onEnd))
                .onMessage(Tick.class, message -> onTick())
                .build();
    }

    private Behavior<BattleMessage> onCreate(BattleCreateCommand command) {
        if (command.battleId() == null || command.battleId().isBlank()
                || !Objects.equals(entityId(), command.battleId())) {
            log.warn("Battle creation rejected: entityId={}, commandBattleId={}", entityId(), command.battleId());
            return this;
        }
        if (state.create()) {
            timers.startTimerAtFixedRate(TICK_KEY, new Tick(), TICK_INTERVAL);
        }
        return this;
    }

    private Behavior<BattleMessage> onJoin(BattleJoinCommand command) {
        String reason = state.join(command.playerId());
        if (reason != null) {
            recordInputRejected(reason);
        }
        return this;
    }

    private Behavior<BattleMessage> onLeave(BattleLeaveCommand command) {
        if (state.leave(command.playerId()) && state.isEmpty()) {
            timers.cancel(TICK_KEY);
            return Behaviors.stopped();
        }
        return this;
    }

    private Behavior<BattleMessage> onInput(BattleInputCommand command) {
        String reason = state.acceptInput(command);
        if (reason != null) {
            recordInputRejected(reason);
        }
        return this;
    }

    private Behavior<BattleMessage> onEnd(BattleEndCommand command) {
        if (state.end()) {
            timers.cancel(TICK_KEY);
            log.info("Battle ended: battleId={}, reason={}, frame={}", entityId(), command.reason(), state.frameNumber());
            return Behaviors.stopped();
        }
        return this;
    }

    private Behavior<BattleMessage> onTick() {
        long started = System.nanoTime();
        state.tick();
        long elapsed = System.nanoTime() - started;
        Metrics.timer(METRIC_TICK_DURATION).record(elapsed, TimeUnit.NANOSECONDS);
        if (elapsed > TICK_BUDGET_NANOS) {
            Metrics.counter(METRIC_TICK_OVERRUN).increment();
        }
        return this;
    }

    private void recordInputRejected(String reason) {
        Metrics.counter(METRIC_INPUT_REJECTED, "reason", reason).increment();
    }

    private static final class Tick implements BattleMessage {
    }
}
