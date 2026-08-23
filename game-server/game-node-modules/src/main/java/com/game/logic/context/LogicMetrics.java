package com.game.logic.context;

import com.game.logic.dbsync.DBSyncEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 逻辑服运行指标：DBSyncEngine flush 延迟、dirty 深度、失败计数。
 * 由 GameLogicApplication 装配 MeterRegistry 后创建，并注册到 LogicContext。
 */
public final class LogicMetrics {

    private final Timer flushTimer;
    private final Counter flushFailureCounter;

    public LogicMetrics(MeterRegistry registry, DBSyncEngine dbSyncEngine) {
        this.flushTimer = Timer.builder("dbsync.flush.duration")
                .description("DBSyncEngine flush 耗时")
                .register(registry);
        this.flushFailureCounter = Counter.builder("dbsync.flush.failures")
                .description("DBSyncEngine flush 分片失败次数")
                .register(registry);
        Gauge.builder("dbsync.dirty.size", dbSyncEngine, DBSyncEngine::dirtySize)
                .description("当前待同步脏记录数")
                .register(registry);
    }

    /** 上报一次 flush 耗时（纳秒）。 */
    public void recordFlush(long durationNanos) {
        flushTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** 上报一次 flush 分片失败。 */
    public void recordFlushFailure() {
        flushFailureCounter.increment();
    }
}