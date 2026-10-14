package com.game.logic.context;

import com.game.eventsourcing.sync.SyncMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 逻辑服运行指标：DBSyncEngine flush 延迟、dirty 深度、失败计数（按 engine tag 区分共享引擎与独立引擎），
 * 以及登录各阶段耗时。由 GameLogicApplication 装配 MeterRegistry 后创建，并注册到 LogicContext。
 */
public final class LogicMetrics {

    private final MeterRegistry registry;
    /** 每个引擎一份同步指标，所有 dbsync.* 指标带 engine tag；同引擎只创建一次。 */
    private final ConcurrentHashMap<String, SyncMetrics> engineMetrics = new ConcurrentHashMap<>();
    private final Timer loginRedisLoadHitTimer;
    private final Timer loginRedisLoadMissTimer;
    private final Timer loginRedisLoadFailureTimer;
    private final Timer loginDatabaseLoadTimer;
    private final Timer loginRedisCreateTimer;
    private final Timer loginLogicTotalTimer;
    private final Timer loginS2cSendTimer;

    public LogicMetrics(MeterRegistry registry) {
        this.registry = registry;
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
        // 记录耗时而非绝对时间戳：绝对 nanoTime 作 Gauge 无法告警。
        this.loginS2cSendTimer = Timer.builder("login.s2c.send.duration")
                .description("登录处理到 S2C 响应发送的耗时")
                .register(registry);
    }

    /** 返回指定引擎的同步指标；引擎名作为 engine tag 打在全部 dbsync.* 指标上。 */
    public SyncMetrics engineMetrics(String engineName) {
        return engineMetrics.computeIfAbsent(engineName, name -> new EngineSyncMetrics(registry, name));
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

    /** 记录登录 S2C 响应发送耗时（仅内部使用，语义由时间戳改为耗时）。 */
    public void recordLoginS2cSend(long durationNanos) {
        loginS2cSendTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /** 单引擎同步指标实现：flush 延迟 / 失败 / 重试耗尽 / 终态回收 / 容量拒绝 / 脏记录积压，全部带 engine tag。 */
    private static final class EngineSyncMetrics implements SyncMetrics {

        private final Timer flushTimer;
        private final Counter flushFailureCounter;
        private final Counter retryExhaustedCounter;
        private final Counter quarantineReclaimedCounter;
        private final Counter submitRejectedCounter;
        private final AtomicInteger dirtySize = new AtomicInteger(0);
        private final AtomicLong dirtyBytes = new AtomicLong(0L);

        EngineSyncMetrics(MeterRegistry registry, String engineName) {
            this.flushTimer = Timer.builder("dbsync.flush.duration")
                    .description("DBSyncEngine flush 耗时")
                    .tag("engine", engineName)
                    .register(registry);
            this.flushFailureCounter = Counter.builder("dbsync.flush.failures")
                    .description("DBSyncEngine flush 分片失败次数")
                    .tag("engine", engineName)
                    .register(registry);
            this.retryExhaustedCounter = Counter.builder("dbsync.retry.exhausted")
                    .description("DBSyncEngine records that reached the retry limit")
                    .tag("engine", engineName)
                    .register(registry);
            this.quarantineReclaimedCounter = Counter.builder("dbsync.quarantine.reclaimed")
                    .description("DBSyncEngine terminal failure records reclaimed after retention")
                    .tag("engine", engineName)
                    .register(registry);
            this.submitRejectedCounter = Counter.builder("dbsync.submit.rejected")
                    .description("DBSyncEngine submit rejected due to capacity limit")
                    .tag("engine", engineName)
                    .register(registry);
            Gauge.builder("dbsync.dirty.size", dirtySize, AtomicInteger::get)
                    .description("当前待同步脏记录数")
                    .tag("engine", engineName)
                    .register(registry);
            Gauge.builder("dbsync.dirty.bytes", dirtyBytes, AtomicLong::get)
                    .description("当前待同步脏记录估算字节数")
                    .tag("engine", engineName)
                    .register(registry);
        }

        @Override
        public void recordFlush(long durationNanos) {
            flushTimer.record(durationNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void recordFlushFailure() {
            flushFailureCounter.increment();
        }

        @Override
        public void recordRetryExhausted() {
            retryExhaustedCounter.increment();
        }

        @Override
        public void recordQuarantineReclaimed() {
            quarantineReclaimedCounter.increment();
        }

        @Override
        public void recordSubmitRejected() {
            submitRejectedCounter.increment();
        }

        @Override
        public void updateDirty(int size, long bytes) {
            dirtySize.set(size);
            dirtyBytes.set(bytes);
        }
    }
}