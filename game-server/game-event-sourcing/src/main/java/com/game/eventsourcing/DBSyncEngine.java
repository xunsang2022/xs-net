package com.game.eventsourcing;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内批写器：收集脏记录，定时批量 UPSERT 到 PostgreSQL。
 * 使用 ConcurrentHashMap 收集脏记录，ScheduledExecutorService 定时触发 flush，
 * flush 按 flushConcurrency 分片并行执行 MyBatis 自定义批量 UPSERT，
 * 成功移除脏记录，失败（异常或乐观锁冲突）保留下一轮重试。
 */
@Slf4j
public class DBSyncEngine {

    private final DBSyncConfig config;
    private final DirtyStateBuffer dirty;
    private final ConcurrentHashMap<Long, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> nextRetryNanos = new ConcurrentHashMap<>();
    private final Set<Long> terminalFailures = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final SyncMetrics metrics;

    /**
     * 持久化回调：由外部注入，用于执行实际的数据库 UPSERT 操作。
     * 解耦了对具体 Mapper 的依赖。
     */
    private final PersistenceCallback persistenceCallback;

    /**
     * 加载回调：由外部注入，用于从数据库加载或创建记录。
     */
    private final LoadCallback loadCallback;

    private ScheduledExecutorService scheduler;
    private ExecutorService flushPool;

    public DBSyncEngine(DBSyncConfig config, SyncMetrics metrics,
                        PersistenceCallback persistenceCallback, LoadCallback loadCallback) {
        this.config = config;
        this.metrics = metrics;
        this.persistenceCallback = persistenceCallback;
        this.loadCallback = loadCallback;
        this.dirty = new DirtyStateBuffer(config.getMaxDirtyRecords(), config.getMaxDirtyBytes());
    }

    /**
     * 启动数据库同步引擎（DB ↔ Redis数据同步）
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-sync-flush-scheduler");
            t.setDaemon(true);
            return t;
        });
        flushPool = Executors.newFixedThreadPool(config.getFlushConcurrency(), r -> {
            Thread t = new Thread(r, "db-sync-flush-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush,
                config.getBgsaveSeconds(), config.getBgsaveSeconds(), TimeUnit.SECONDS);
        log.info("DBSyncEngine started: bgsaveSeconds={}, batchSize={}, flushConcurrency={}",
                config.getBgsaveSeconds(), config.getBatchSize(), config.getFlushConcurrency());
    }

    /**
     * 停止定时 flush，等待在途任务结束。
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        flushPool.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            flushPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("DBSyncEngine stopped");
    }

    /**
     * 提交脏字段：同 playerId 覆盖 fields、更新 version。
     * 脏记录数达到 maxDirtyRecords 时拒绝新提交并返回 false，防止失败持续时内存无限增长。
     */
    public boolean submit(Long playerId, Map<String, String> fields, long version) {
        DirtyStateBuffer.SubmitResult result = dirty.submit(playerId, fields, version);
        if (result == DirtyStateBuffer.SubmitResult.REJECTED_CAPACITY) {
            log.error("DBSyncEngine buffer full, reject new player state: playerId={}, records={}, bytes={}",
                    playerId, dirty.size(), dirty.bytes());
            return false;
        }
        nextRetryNanos.remove(playerId);
        retryAttempts.remove(playerId);
        terminalFailures.remove(playerId);
        return true;
    }

    /**
     * 当前待同步的脏记录数（测试与指标用）。
     */
    public int dirtySize() {
        return dirty.size();
    }

    public long dirtyBytes() {
        return dirty.bytes();
    }

    /**
     * 从 PG 读取记录回填。
     */
    public Map<String, String> loadOrCreate(Long recordId) {
        return loadCallback.loadOrCreate(recordId);
    }

    /** 同步保存完整快照，供下线流程在删除缓存前完成最终落库。 */
    public boolean saveNow(Long recordId, Map<String, String> snapshot, long version) {
        DirtyRecord record = new DirtyRecord(
                recordId,
                snapshot,
                Math.max(0L, version - 1L),
                version);
        return persistenceCallback.upsertRecord(record) != null;
    }

    /**
     * 定时批量 UPSERT：按 flushConcurrency 分片并行执行，成功移除脏记录，失败保留重试。
     * 未 start() 时（如单元测试）同步执行。
     */
    public void flush() {
        if (dirty.size() == 0) {
            return;
        }
        long startNanos = System.nanoTime();
        long now = System.nanoTime();
        List<DirtyRecord> snapshot = dirty.snapshot(config.getMaxDirtyRecords()).stream()
                .filter(record -> !terminalFailures.contains(record.playerId()))
                .filter(record -> nextRetryNanos.getOrDefault(record.playerId(), 0L) <= now)
                .toList();
        List<List<DirtyRecord>> shards = shard(snapshot, config.getFlushConcurrency());
        if (flushPool == null) {
            for (List<DirtyRecord> shard : shards) {
                if (!shard.isEmpty()) {
                    flushShard(shard);
                }
            }
            reportFlush(startNanos);
            return;
        }
        List<Future<?>> futures = new ArrayList<>();
        for (List<DirtyRecord> shard : shards) {
            if (shard.isEmpty()) {
                continue;
            }
            futures.add(flushPool.submit(() -> flushShard(shard)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("flush shard interrupted/failed", e);
            }
        }
        reportFlush(startNanos);
    }

    private void reportFlush(long startNanos) {
        if (metrics != null) {
            metrics.recordFlush(System.nanoTime() - startNanos);
        }
    }

    private void flushShard(List<DirtyRecord> shard) {
        try {
            for (int offset = 0; offset < shard.size(); offset += config.getBatchSize()) {
                List<DirtyRecord> batch = shard.subList(offset,
                        Math.min(offset + config.getBatchSize(), shard.size()));
                List<Long> persistedIds = persistenceCallback.upsertBatch(batch);
                for (DirtyRecord record : batch) {
                    if (persistedIds.contains(record.playerId())) {
                        dirty.removeIfVersion(record.playerId(), record.version());
                        retryAttempts.remove(record.playerId());
                        nextRetryNanos.remove(record.playerId());
                        terminalFailures.remove(record.playerId());
                    } else {
                        // PostgreSQL RETURNING 不返回乐观锁冲突行，保持记录并退避重试。
                        scheduleRetry(record);
                    }
                }
            }
        } catch (Exception e) {
            log.error("flush shard failed, {} records retained for retry", shard.size(), e);
            if (metrics != null) {
                metrics.recordFlushFailure();
            }
            for (DirtyRecord record : shard) {
                scheduleRetry(record);
            }
        }
    }

    private void scheduleRetry(DirtyRecord record) {
        int attempts = retryAttempts.merge(record.playerId(), 1, Integer::sum);
        long multiplier = 1L << Math.min(attempts - 1, 10);
        long delayMs = Math.min(60_000L, config.getRetryBackoffMs() * multiplier);
        nextRetryNanos.put(record.playerId(),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs));
        if (attempts >= config.getMaxRetryAttempts()) {
            terminalFailures.add(record.playerId());
            if (metrics != null) {
                metrics.recordRetryExhausted();
            }
            log.error("DBSyncEngine record exceeded retry threshold: playerId={}, attempts={}",
                    record.playerId(), attempts);
        }
    }

    private List<List<DirtyRecord>> shard(List<DirtyRecord> records, int shardCount) {
        List<List<DirtyRecord>> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            shards.add(new ArrayList<>());
        }
        for (int i = 0; i < records.size(); i++) {
            shards.get(i % shardCount).add(records.get(i));
        }
        return shards;
    }

    /**
     * 持久化回调接口：解耦 DBSyncEngine 与具体 Mapper 实现。
     */
    public interface PersistenceCallback {
        Long upsertRecord(DirtyRecord record);
        List<Long> upsertBatch(List<DirtyRecord> records);
    }

    /**
     * 加载回调接口：解耦 DBSyncEngine 与具体 Mapper 实现。
     */
    public interface LoadCallback {
        Map<String, String> loadOrCreate(Long recordId);
    }
}
