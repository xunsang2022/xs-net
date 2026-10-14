package com.game.eventsourcing.sync;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内批写器：收集脏记录，定时批量 UPSERT 到 PostgreSQL。
 * 使用 ConcurrentHashMap 收集脏记录，ScheduledExecutorService 定时触发 flush，
 * flush 按 flushConcurrency 分片并行执行 MyBatis 自定义批量 UPSERT，
 * 成功移除脏记录，失败（异常或乐观锁冲突）保留下一轮重试。
 *
 * <p>脏记录以 {@link RecordKey}（entityType + entityId）为键，同一引擎可承载多个实体类型；
 * 多实体由 {@link EntityPersistenceRouter} 按 entityType 分发到各自的 {@link EntityMapper}。</p>
 */
@Slf4j
public class DBSyncEngine {

    private final DBSyncConfig config;
    private final SyncMetrics metrics;
    private final PersistenceCallback persistenceCallback;
    private final LoadCallback loadCallback;
    private final DirtyStateBuffer dirty;

    /**
     * P1: 每个实体键的重试状态合并为一个不可变 record，用单个 ConcurrentHashMap 管理。
     * 相比之前两个独立 map（retryAttempts + nextRetryNanos），单 map 避免了两者不一致：
     * 比如"attempts 已清但 nextRetryNanos 未清"的窗口。
     * 所有更新走 compute / remove，ConcurrentHashMap 保证单 key 操作的原子性。
     */
    private record RetryState(int attempts, long nextRetryNanos) {}

    private final ConcurrentHashMap<RecordKey, RetryState> retryStates = new ConcurrentHashMap<>();

    /** 彻底放弃的实体键 */
    private final Set<RecordKey> terminalFailures = ConcurrentHashMap.newKeySet();
    /** 实体键 -> 进入 terminalFailures 的时间戳（纳秒），用于保留期清理 */
    private final ConcurrentHashMap<RecordKey, Long> terminalSinceNanos = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 保证同一时刻只有一轮 flush 在跑 */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    /** 定时触发 flush */
    private ScheduledExecutorService scheduler;
    /** flush 分片并行池 */
    private ExecutorService flushPool;

    public DBSyncEngine(
            DBSyncConfig config, SyncMetrics metrics,
            PersistenceCallback persistenceCallback, LoadCallback loadCallback
    ) {
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
            return; // 已在运行，幂等
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
        log.info("DBSyncEngine started: bgsaveSeconds={}, batchSize={}, flushConcurrency={}, flushTimeoutMs={}",
                config.getBgsaveSeconds(), config.getBatchSize(), config.getFlushConcurrency(),
                config.getFlushTimeoutMs());
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
            if (!scheduler.awaitTermination(config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("DBSyncEngine scheduler did not terminate within {} ms, forcing shutdownNow",
                        config.getShutdownTimeoutMs());
                scheduler.shutdownNow();
            }
            if (!flushPool.awaitTermination(config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("DBSyncEngine flushPool did not terminate within {} ms, forcing shutdownNow",
                        config.getShutdownTimeoutMs());
                flushPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
            flushPool.shutdownNow();
        }
        log.info("DBSyncEngine stopped");
    }

    /**
     * 提交脏字段：同 (entityType, entityId) 覆盖 fields、更新 version。
     * 脏记录数或字节数达到上限时拒绝新提交并返回 false，防止失败持续时内存无限增长。
     *
     * <p><b>调用方契约</b>：返回 false 表示本次提交未被引擎接受，调用方必须处理
     * （重试、降级到同步写、或记录到待补偿队列）。忽略返回值会导致数据静默丢失。
     */
    public boolean submit(String entityType, Long entityId, Map<String, String> fields, long version) {
        DirtyStateBuffer.SubmitResult result = dirty.submit(entityType, entityId, fields, version);
        if (result == DirtyStateBuffer.SubmitResult.REJECTED_CAPACITY) {
            log.error("DBSyncEngine buffer full, reject new entity state: entityType={}, entityId={}, records={}, bytes={}",
                    entityType, entityId, dirty.size(), dirty.bytes());
            if (metrics != null) {
                metrics.recordSubmitRejected();
            }
            return false;
        }
        // P1: 有新提交 → 重置重试状态。retryStates 是单 map，remove 与 flush 的 compute
        // 通过 ConcurrentHashMap 单 key 原子性保证不会出现"清了一半"的不一致。
        // 最坏情况：flush 的 compute 先执行、remove 后执行，下一轮 flush 重新从 0 计数——
        // 这正是"新提交重置重试"的期望语义。
        RecordKey key = new RecordKey(entityType, entityId);
        retryStates.remove(key);
        clearTerminal(key);
        return true;
    }

    /**
     * 从 PG 读取记录回填。
     */
    public Map<String, String> loadOrCreate(String entityType, Long entityId) {
        return loadCallback.loadOrCreate(entityType, entityId);
    }

    /** 同步保存完整快照，供下线流程在删除缓存前完成最终落库。 */
    public boolean saveNow(String entityType, Long entityId, Map<String, String> snapshot, long version) {
        if (entityType == null || entityId == null || snapshot == null) {
            throw new IllegalArgumentException("entityType, entityId and snapshot are required");
        }
        DirtyRecord record = new DirtyRecord(
                entityType,
                entityId,
                new LinkedHashMap<>(snapshot), // 防御性拷贝，避免调用方后续修改影响记录
                Math.max(0L, version - 1L),
                version);
        RecordKey persisted = persistenceCallback.upsertRecord(record);
        if (persisted == null) {
            return false;
        }
        // saveNow 是最终落库路径：成功后该实体键不应再有任何待同步脏记录。
        // 无条件移除（不看版本），避免下一轮 flush 重复写或用旧版本覆盖新状态。
        // 若此时并发 submit 了新状态，下一轮 flush 会重新写入，不会丢数据。
        RecordKey key = record.key();
        dirty.remove(key);
        retryStates.remove(key);
        clearTerminal(key);
        return true;
    }

    /**
     * 定时批量 UPSERT：按 flushConcurrency 分片并行执行，成功移除脏记录，失败保留重试。
     * 未 start() 时（如单元测试）同步执行。
     */
    public void flush() {
        // 保证同一时刻只有一轮 flush；重入直接跳过，等下一轮调度
        if (!flushing.compareAndSet(false, true)) {
            log.debug("flush already in progress, skip this round");
            return;
        }
        try {
            doFlush();
        } finally {
            flushing.set(false);
        }
    }

    private void doFlush() {
        // 先做终态清理，释放 buffer 容量
        reclaimTerminalFailures();
        reportDirty();

        if (dirty.size() == 0) {
            return;
        }
        long startNanos = System.nanoTime();
        List<List<DirtyRecord>> shards = shard(selectFlushableRecords(System.nanoTime()), config.getFlushConcurrency());
        executeShards(shards);
        reportFlush(startNanos);
    }

    /** 选出本轮可刷写的脏记录：排除终态失败与退避未到期的记录。 */
    private List<DirtyRecord> selectFlushableRecords(long now) {
        return dirty.snapshot(config.getMaxDirtyRecords()).stream()
                .filter(record -> !terminalFailures.contains(record.key()))
                .filter(record -> {
                    RetryState rs = retryStates.get(record.key());
                    return rs == null || rs.nextRetryNanos() <= now;
                })
                .toList();
    }

    /** 执行各分片：未 start() 时同步执行，否则提交到 flushPool 并行执行。 */
    private void executeShards(List<List<DirtyRecord>> shards) {
        if (flushPool == null) {
            for (List<DirtyRecord> shard : shards) {
                if (!shard.isEmpty()) {
                    flushShard(shard);
                }
            }
            return;
        }
        List<Future<?>> futures = new ArrayList<>();
        for (List<DirtyRecord> shard : shards) {
            if (shard.isEmpty()) {
                continue;
            }
            try {
                futures.add(flushPool.submit(() -> flushShard(shard)));
            } catch (RejectedExecutionException e) {
                // stop() 与 in-flight flush 的竞争窗口：线程池已 shutdown。
                // 同步执行，保证这批记录不被静默丢弃（要么成功，要么走重试）。
                log.warn("flushPool rejected task, execute shard synchronously: size={}", shard.size());
                flushShard(shard);
            }
        }
        awaitShards(futures);
    }

    /** 等待各分片完成；分片超时或失败时保留记录走重试。 */
    private void awaitShards(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                // 加超时，避免某个分片卡死拖垮整个 flush
                f.get(config.getFlushTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.error("flush shard timed out after {} ms, cancel and let records retry",
                        config.getFlushTimeoutMs());
                f.cancel(true);
                if (metrics != null) {
                    metrics.recordFlushFailure();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("flush shard wait interrupted", e);
            } catch (ExecutionException e) {
                log.error("flush shard execution failed", e);
            }
        }
    }

    private void reportFlush(long startNanos) {
        if (metrics != null) {
            metrics.recordFlush(System.nanoTime() - startNanos);
        }
    }

    /** flush 触发时上报本引擎的脏记录积压量（指标实现按 engine tag 区分引擎）。 */
    private void reportDirty() {
        if (metrics != null) {
            metrics.updateDirty(dirty.size(), dirty.bytes());
        }
    }

    private void flushShard(List<DirtyRecord> shard) {
        // 逐批捕获异常，只对未成功记录排重试，避免已成功记录被重复计数
        for (int offset = 0; offset < shard.size(); offset += config.getBatchSize()) {
            int end = Math.min(offset + config.getBatchSize(), shard.size());
            List<DirtyRecord> batch = shard.subList(offset, end);
            Set<RecordKey> persistedKeys;
            try {
                List<RecordKey> raw = persistenceCallback.upsertBatch(batch);
                // 防御：只信任 batch 内的实体键，避免 callback 实现返回超集导致误删。
                Set<RecordKey> batchKeys = new HashSet<>(batch.size());
                for (DirtyRecord r : batch) {
                    batchKeys.add(r.key());
                }
                if (raw == null || raw.isEmpty()) {
                    persistedKeys = Set.of();
                } else {
                    persistedKeys = new HashSet<>(raw);
                    persistedKeys.retainAll(batchKeys);
                }
            } catch (RuntimeException e) {
                log.error("flush batch failed, {} records retained for retry", batch.size(), e);
                if (metrics != null) {
                    metrics.recordFlushFailure();
                }
                for (DirtyRecord record : batch) {
                    scheduleRetry(record);
                }
                continue; // 下一批继续，不让整 shard 一起失败
            }
            for (DirtyRecord record : batch) {
                if (persistedKeys.contains(record.key())) {
                    dirty.removeIfVersion(record.key(), record.version());
                    retryStates.remove(record.key());
                    clearTerminal(record.key());
                } else {
                    // PostgreSQL RETURNING 不返回乐观锁冲突行，保持记录并退避重试。
                    scheduleRetry(record);
                }
            }
        }
    }

    /**
     * P1: 用 compute 原子更新重试状态。
     * 与 submit 的 retryStates.remove 是单 key 竞争，语义安全（见 submit 注释）。
     */
    private void scheduleRetry(DirtyRecord record) {
        RetryState updated = retryStates.compute(record.key(), (key, old) -> {
            int attempts = (old == null ? 0 : old.attempts()) + 1;
            long multiplier = 1L << Math.min(attempts - 1, 10);
            long delayMs = Math.min(config.getMaxRetryBackoffMs(), config.getRetryBackoffMs() * multiplier);
            long next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
            return new RetryState(attempts, next);
        });
        if (updated != null && updated.attempts() >= config.getMaxRetryAttempts()) {
            markTerminal(record.key(), updated.attempts());
        }
    }

    /**
     * 标记为终态失败，并记录进入时间，便于保留期后回收。
     * 注意顺序：先 put 时间戳，再 add 到 set。这样 reclaimTerminalFailures 遍历 set 时
     * 必然能读到时间戳（ConcurrentHashMap 的 put happens-before 后续的 add 可见性）。
     */
    private void markTerminal(RecordKey key, int attempts) {
        terminalSinceNanos.put(key, System.nanoTime());
        terminalFailures.add(key);
        if (metrics != null) {
            metrics.recordRetryExhausted();
        }
        log.error("DBSyncEngine record exceeded retry threshold: entityType={}, entityId={}, attempts={}",
                key.entityType(), key.entityId(), attempts);
    }

    /** 清理某个实体键的终态标记（成功写入 / 有新提交时调用）。 */
    private void clearTerminal(RecordKey key) {
        terminalFailures.remove(key);
        terminalSinceNanos.remove(key);
    }

    /**
     * 回收超过保留期的终态失败记录，释放 buffer 容量，避免死记录撑满 maxDirtyRecords。
     * 回收时同步从 dirty 中无条件移除（不看版本），并递增一个"放弃"指标，便于告警/人工排查。
     */
    private void reclaimTerminalFailures() {
        if (terminalFailures.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long retentionNanos = TimeUnit.MILLISECONDS.toNanos(config.getQuarantineRetentionMs());
        List<RecordKey> reclaimable = new ArrayList<>();
        for (RecordKey key : terminalFailures) {
            Long since = terminalSinceNanos.get(key);
            if (since == null || now - since >= retentionNanos) {
                reclaimable.add(key);
            }
        }
        for (RecordKey key : reclaimable) {
            // 无条件移除脏记录：终态回收语义是"放弃该实体键的所有待同步状态"，
            // 不应受版本变化影响。dirty.remove 是原子的，避免 get + removeIfVersion 的竞争。
            dirty.remove(key);
            retryStates.remove(key);
            terminalFailures.remove(key);
            terminalSinceNanos.remove(key);
            if (metrics != null) {
                metrics.recordQuarantineReclaimed();
            }
            log.warn("DBSyncEngine reclaimed terminal failure record after retention: entityType={}, entityId={}",
                    key.entityType(), key.entityId());
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
     * 持久化回调接口：解耦 DBSyncEngine 与具体持久化实现。
     */
    public interface PersistenceCallback {

        /**
         * 单条 UPSERT。
         *
         * @return 成功写入的实体键；null 表示未写入（如乐观锁冲突），调用方保留重试。
         */
        RecordKey upsertRecord(DirtyRecord record);

        /**
         * 批量 UPSERT。
         *
         * @return 本批次中**成功写入且未发生乐观锁冲突**的实体键集合。
         *         实现必须保证：
         *         1. 返回值仅包含本批次 records 中的实体键，不得包含其他记录；
         *         2. 发生乐观锁冲突（PostgreSQL RETURNING 未返回该行）的实体键不得出现在返回值中；
         *         3. 不得返回 null（无成功记录时返回空集合）。
         *         调用方依赖此契约决定哪些脏记录可以安全移除。
         */
        List<RecordKey> upsertBatch(List<DirtyRecord> records);
    }

    /**
     * 加载回调接口：解耦 DBSyncEngine 与具体持久化实现。
     */
    public interface LoadCallback {
        Map<String, String> loadOrCreate(String entityType, Long entityId);
    }
}