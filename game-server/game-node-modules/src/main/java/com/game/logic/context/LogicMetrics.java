package com.game.logic.context;

import com.game.logic.dbsync.DBSyncEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 逻辑服运行指标：DBSyncEngine flush 延迟、dirty 深度、失败计数。
 * 由 GameLogicApplication 装配 MeterRegistry 后创建，并注册到 LogicContext。
 */
public final class LogicMetrics {

    private final Timer flushTimer;
    private final Counter flushFailureCounter;
    private final Timer loginRedisLoadHitTimer;
    private final Timer loginRedisLoadMissTimer;
    private final Timer loginRedisLoadFailureTimer;
    private final Timer loginDatabaseLoadTimer;
    private final Timer loginRedisCreateTimer;
    private final Timer loginLogicTotalTimer;
    private final AtomicLong loginS2cSendNanos = new AtomicLong(0L);

    public LogicMetrics(MeterRegistry registry, DBSyncEngine dbSyncEngine) {
        this.flushTimer = Timer.builder("dbsync.flush.duration")
                .description("DBSyncEngine flush 耗时")
                .register(registry);
        this.flushFailureCounter = Counter.builder("dbsync.flush.failures")
                .description("DBSyncEngine flush 分片失败次数")
                .register(registry);
        this.loginRedisLoadHitTimer = Timer.builder("login.redis.load.duration")
                .tag("cache", "hit")
                .register(registry);
        this.loginRedisLoadMissTimer = Timer.builder("login.redis.load.duration")
                .tag("cache", "miss")
                .register(registry);
        this.loginRedisLoadFailureTimer = Timer.builder("login.redis.load.duration")
                .tag("cache", "failure")
                .register(registry);
        this.loginDatabaseLoadTimer = Timer.builder("login.database.load.duration")
                .register(registry);
        this.loginRedisCreateTimer = Timer.builder("login.redis.create.duration")
                .register(registry);
        this.loginLogicTotalTimer = Timer.builder("login.logic.total.duration")
                .register(registry);
        Gauge.builder("login.s2c.send.nano", loginS2cSendNanos, AtomicLong::get)
                .description("PlayerActor ready to send S2C when nanoTime()")
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

    public void recordLoginRedisLoad(long durationNanos, boolean cacheHit) {
        Timer timer = cacheHit ? loginRedisLoadHitTimer : loginRedisLoadMissTimer;
        timer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordLoginRedisLoadFailure(long durationNanos) {
        loginRedisLoadFailureTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordLoginDatabaseLoad(long durationNanos) {
        loginDatabaseLoadTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordLoginRedisCreate(long durationNanos) {
        loginRedisCreateTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordLoginLogicTotal(long durationNanos) {
        loginLogicTotalTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void setLoginS2cSendNanos(long nanoTime) {
        loginS2cSendNanos.set(nanoTime);
    }
}