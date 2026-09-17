package com.game.battle.actor;

import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleInputCommand;
import com.xs.net.message.RequestContext;
import io.micrometer.core.instrument.Metrics;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated battle execution pool. Each battle is serialized by its own lane. */
public final class BattleExecutorPool implements AutoCloseable {
    private final BattleExecutorConfig config;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService ticker;
    private final ConcurrentMap<String, BattleRuntime> runtimes = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger activeLanes = new AtomicInteger();

    public BattleExecutorPool(BattleExecutorConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = new ThreadPoolExecutor(
                config.poolSize(),
                config.poolSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(config.poolSize(), config.poolSize() * 64)),
                namedFactory("battle-executor-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.ticker = Executors.newSingleThreadScheduledExecutor(namedFactory("battle-ticker-"));
        Metrics.gauge("battle.executor.active_lanes", activeLanes);
    }

    public BattleExecutorConfig config() {
        return config;
    }

    public BattleRuntime activate(String battleId) {
        Objects.requireNonNull(battleId, "battleId");
        if (!accepting.get()) {
            throw new IllegalStateException("Battle executor is closed");
        }
        return runtimes.computeIfAbsent(battleId, id -> {
            activeLanes.incrementAndGet();
            return new BattleRuntime(id, config, this::dispatch, ticker, this::remove);
        });
    }

    public BattleRuntime find(String battleId) {
        return runtimes.get(battleId);
    }

    public int activeLanes() {
        return activeLanes.get();
    }

    public BattleSubmissionResult submit(String battleId, Object command, RequestContext context) {
        if (!accepting.get()) {
            return BattleSubmissionResult.CLOSED;
        }
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(command, "command");
        BattleRuntime runtime = runtimes.get(battleId);
        if (runtime == null && command instanceof BattleCreateCommand) {
            runtime = activate(battleId);
        }
        if (runtime == null) {
            return BattleSubmissionResult.UNKNOWN_BATTLE;
        }
        if (command instanceof BattleInputCommand input) {
            return runtime.submitInput(input, context);
        }
        return runtime.submitControl(command, context);
    }

    boolean dispatch(Runnable task) {
        if (!accepting.get()) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }

    private void remove(String battleId, BattleRuntime runtime) {
        if (runtimes.remove(battleId, runtime)) {
            activeLanes.decrementAndGet();
        }
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        runtimes.values().forEach(BattleRuntime::close);
        ticker.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.shutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        runtimes.clear();
        activeLanes.set(0);
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
