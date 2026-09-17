package com.game.battle.actor;

import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleEndCommand;
import com.game.message.battle.BattleInputCommand;
import com.game.message.battle.BattleJoinCommand;
import com.game.message.battle.BattleLeaveCommand;
import com.xs.net.message.RequestContext;
import com.xs.net.message.RequestContextHolder;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Single-threaded execution lane for one battle. */
public final class BattleRuntime implements AutoCloseable {
    private static final int MAX_PLAYERS = 100;
    private static final long TICK_BUDGET_NANOS = 16_666_667L;

    private final String battleId;
    private final BattleExecutorConfig config;
    private final ArrayBlockingQueue<Envelope> controls;
    private final ConcurrentMap<Long, Envelope> latestInputs = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean tickPending = new AtomicBoolean();
    private final AtomicBoolean tickStarted = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicInteger queuedInputs = new AtomicInteger();
    private final AtomicInteger activeDrains = new AtomicInteger();
    private final AtomicInteger maxConcurrentDrains = new AtomicInteger();
    private volatile String lastExecutionThreadName;
    private final BattleState state;
    private final Predicate<Runnable> dispatcher;
    private final ScheduledExecutorService ticker;
    private final BiConsumer<String, BattleRuntime> onClosed;
    private final AtomicReference<ScheduledFuture<?>> tickFuture = new AtomicReference<>();

    BattleRuntime(
            String battleId,
            BattleExecutorConfig config,
            Predicate<Runnable> dispatcher,
            ScheduledExecutorService ticker,
            BiConsumer<String, BattleRuntime> onClosed
    ) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.config = Objects.requireNonNull(config, "config");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        this.controls = new ArrayBlockingQueue<>(config.controlQueueCapacity());
        this.state = new BattleState(battleId, MAX_PLAYERS);
        Metrics.gauge("battle.executor.queue_depth", Tags.of("battleId", battleId), this,
                runtime -> runtime.controls.size() + runtime.queuedInputs.get());
    }

    public String battleId() {
        return battleId;
    }

    public int controlQueueDepth() {
        return controls.size();
    }

    public int pendingInputCount() {
        return latestInputs.size();
    }

    Set<Long> pendingInputPlayers() {
        return Set.copyOf(latestInputs.keySet());
    }

    public long frameNumber() {
        return state.frameNumber();
    }

    BattleSubmissionResult submitControl(Object command, RequestContext context) {
        if (!accepting.get()) {
            return BattleSubmissionResult.CLOSED;
        }
        if (context != null && context.expired()) {
            record("battle.executor.control_rejected", context, null, "deadline_expired");
            return BattleSubmissionResult.REJECTED_INPUT_CAPACITY;
        }
        if (!controls.offer(new Envelope(command, context))) {
            record("battle.executor.control_rejected", context, null, "capacity");
            return BattleSubmissionResult.REJECTED_CONTROL_CAPACITY;
        }
        if (!schedule()) {
            return BattleSubmissionResult.EXECUTOR_REJECTED;
        }
        return BattleSubmissionResult.ACCEPTED;
    }

    BattleSubmissionResult submitInput(BattleInputCommand input, RequestContext context) {
        if (!accepting.get()) {
            return BattleSubmissionResult.CLOSED;
        }
        if (context != null && context.expired()) {
            record("battle.input.rejected", context, input.playerId(), "deadline_expired");
            return BattleSubmissionResult.REJECTED_INPUT_CAPACITY;
        }
        if (input.playerId() == null) {
            record("battle.input.rejected", context, null, "missing_player");
            return BattleSubmissionResult.REJECTED_INPUT_CAPACITY;
        }
        AtomicReference<BattleSubmissionResult> result = new AtomicReference<>();
        latestInputs.compute(input.playerId(), (playerId, previous) -> {
            if (previous == null && latestInputs.size() >= MAX_PLAYERS) {
                result.set(BattleSubmissionResult.REJECTED_INPUT_CAPACITY);
                return null;
            }
            if (previous != null && previous.command() instanceof BattleInputCommand old
                    && input.clientSequence() != null
                    && old.clientSequence() != null
                    && input.clientSequence() <= old.clientSequence()) {
                result.set(BattleSubmissionResult.STALE);
                return previous;
            }
            result.set(previous == null ? BattleSubmissionResult.ACCEPTED : BattleSubmissionResult.COALESCED);
            return new Envelope(input, context);
        });
        BattleSubmissionResult submission = result.get();
        if (submission == BattleSubmissionResult.COALESCED) {
            record("battle.input.coalesced", context, input.playerId(), "latest_wins");
        } else if (submission == BattleSubmissionResult.STALE) {
            record("battle.input.stale", context, input.playerId(), "sequence");
        } else if (submission == BattleSubmissionResult.REJECTED_INPUT_CAPACITY) {
            record("battle.input.rejected", context, input.playerId(), "capacity");
        }
        if (submission == BattleSubmissionResult.ACCEPTED || submission == BattleSubmissionResult.COALESCED) {
            queuedInputs.set(latestInputs.size());
            if (!schedule()) {
                return BattleSubmissionResult.EXECUTOR_REJECTED;
            }
        }
        return submission;
    }

    void requestTick() {
        if (!accepting.get()) {
            return;
        }
        if (!tickPending.compareAndSet(false, true)) {
            record("battle.tick.skipped", null, null, "pending");
            return;
        }
        schedule();
    }

    private boolean schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return true;
        }
        if (dispatcher.test(this::drain)) {
            return true;
        }
        scheduled.set(false);
        record("battle.executor.task_rejected", null, null, "executor_capacity");
        scheduleRetry();
        return false;
    }

    private void scheduleRetry() {
        if (!accepting.get() || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            ticker.schedule(() -> {
                retryScheduled.set(false);
                if (accepting.get() && (!controls.isEmpty() || !latestInputs.isEmpty() || tickPending.get())) {
                    schedule();
                }
            }, 1L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException exception) {
            retryScheduled.set(false);
            if (accepting.get()) {
                record("battle.executor.task_rejected", null, null, "retry_scheduler_unavailable");
            }
        }
    }

    private void drain() {
        long started = System.nanoTime();
        int active = activeDrains.incrementAndGet();
        maxConcurrentDrains.accumulateAndGet(active, Math::max);
        lastExecutionThreadName = Thread.currentThread().getName();
        try {
            int processed = 0;
            while (processed < config.maxInputsPerDrain()) {
                Envelope control = controls.poll();
                if (control == null) {
                    break;
                }
                RequestContextHolder.call(control.context(), () -> {
                    applyControl(control);
                    return null;
                });
                processed++;
                if (!accepting.get()) {
                    return;
                }
            }

            if (tickPending.compareAndSet(true, false)) {
                long tickStarted = System.nanoTime();
                state.tick();
                long elapsed = System.nanoTime() - tickStarted;
                Metrics.timer("battle.tick.duration", "battleId", battleId).record(elapsed,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
                if (elapsed > TICK_BUDGET_NANOS) {
                    Metrics.counter("battle.executor.overrun", "battleId", battleId).increment();
                }
            }

            int inputs = 0;
            for (Map.Entry<Long, Envelope> entry : latestInputs.entrySet()) {
                if (inputs++ >= config.maxInputsPerDrain()) {
                    break;
                }
                if (latestInputs.remove(entry.getKey(), entry.getValue())
                        && entry.getValue().command() instanceof BattleInputCommand input) {
                    String reason = RequestContextHolder.call(entry.getValue().context(),
                            () -> state.acceptInput(input));
                    if (reason != null) {
                        record("battle.input.rejected", entry.getValue().context(), input.playerId(), reason);
                    }
                }
            }
            queuedInputs.set(latestInputs.size());
        } finally {
            Metrics.timer("battle.executor.task_latency", "battleId", battleId).record(
                    System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            scheduled.set(false);
            activeDrains.decrementAndGet();
            if (accepting.get() && (!controls.isEmpty() || !latestInputs.isEmpty() || tickPending.get())) {
                schedule();
            }
        }
    }

    private void applyControl(Envelope envelope) {
        Object command = envelope.command();
        if (command instanceof BattleCreateCommand create) {
            if (state.create()) {
                startTick();
            }
        } else if (command instanceof BattleJoinCommand join) {
            state.join(join.playerId());
        } else if (command instanceof BattleLeaveCommand leave) {
            if (state.leave(leave.playerId()) && state.isEmpty()) {
                close();
            }
        } else if (command instanceof BattleEndCommand end && state.end()) {
            close();
        }
    }

    private void startTick() {
        if (!tickStarted.compareAndSet(false, true)) {
            return;
        }
        tickFuture.set(ticker.scheduleAtFixedRate(this::requestTick,
                config.tickPeriodNanos(), config.tickPeriodNanos(),
                java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    int maxConcurrentDrains() {
        return maxConcurrentDrains.get();
    }

    boolean isTickPending() {
        return tickPending.get();
    }

    boolean accepting() {
        return accepting.get();
    }

    String lastExecutionThreadName() {
        return lastExecutionThreadName;
    }

    private void record(String metric, RequestContext context, Long playerId, String reason) {
        Metrics.counter(metric,
                "battleId", battleId,
                "playerId", playerId == null ? "unknown" : String.valueOf(playerId),
                "traceId", context == null ? "unknown" : context.traceId(),
                "reason", reason).increment();
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> future = tickFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        controls.clear();
        latestInputs.clear();
        queuedInputs.set(0);
        onClosed.accept(battleId, this);
    }

    private record Envelope(Object command, RequestContext context) {
    }
}
