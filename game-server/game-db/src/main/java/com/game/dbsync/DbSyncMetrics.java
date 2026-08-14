package com.game.dbsync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DbSyncMetrics {
    private final AtomicLong dirtyDepth = new AtomicLong();
    private final AtomicLong logBacklog = new AtomicLong();
    private final AtomicInteger flushConcurrency = new AtomicInteger();
    private final Timer flushRoundTimer;
    private final Counter flushFailures;
    private final Counter logsFlushed;
    private final Counter loadFailures;

    public DbSyncMetrics(MeterRegistry registry) {
        Gauge.builder("game.db.player.dirty.depth", dirtyDepth, AtomicLong::get).register(registry);
        Gauge.builder("game.db.log.backlog", logBacklog, AtomicLong::get).register(registry);
        Gauge.builder("game.db.player.flush.concurrency", flushConcurrency, AtomicInteger::get).register(registry);
        flushRoundTimer = Timer.builder("game.db.player.flush.round.duration").register(registry);
        flushFailures = Counter.builder("game.db.player.flush.failures").register(registry);
        logsFlushed = Counter.builder("game.db.log.flushed.total").register(registry);
        loadFailures = Counter.builder("game.db.player.load.failures").register(registry);
    }

    public void updateDirtyDepth(int depth) {
        dirtyDepth.set(depth);
    }

    public void updateLogBacklog(long backlog) {
        logBacklog.set(backlog);
    }

    public void updateFlushConcurrency(int concurrency) {
        flushConcurrency.set(concurrency);
    }

    public Timer.Sample startFlushRound() {
        return Timer.start();
    }

    public void stopFlushRound(Timer.Sample sample) {
        sample.stop(flushRoundTimer);
    }

    public void recordFlushFailure() {
        flushFailures.increment();
    }

    public void recordLogsFlushed(int count) {
        logsFlushed.increment(count);
    }

    public void recordLoadFailure() {
        loadFailures.increment();
    }
}
