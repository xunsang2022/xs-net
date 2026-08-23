package com.game.logic.dbsync;

import com.game.logic.context.LogicContext;
import com.game.logic.context.LogicMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * flush 按 flushConcurrency 分片并行执行 JdbcTemplate.batchUpdate，
 * 成功移除脏记录，失败（异常或乐观锁冲突）保留下一轮重试。
 */
@Slf4j
public class DBSyncEngine {

    private final JdbcTemplate jdbcTemplate;
    private final DBSyncConfig config;
    private final ConcurrentHashMap<Long, DirtyRecord> dirty = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ExecutorService flushPool;

    public DBSyncEngine(JdbcTemplate jdbcTemplate, DBSyncConfig config) {
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
    }

    /**
     * 启动定时 flush。
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
        if (dirty.size() >= config.getMaxDirtyRecords()) {
            log.error("DBSyncEngine dirty map full (maxDirtyRecords={}), reject submit playerId={}",
                    config.getMaxDirtyRecords(), playerId);
            return false;
        }
        dirty.compute(playerId, (k, existing) -> {
            if (existing == null) {
                return new DirtyRecord(playerId, new LinkedHashMap<>(fields), version);
            }
            Map<String, String> merged = new LinkedHashMap<>(existing.fields());
            merged.putAll(fields);
            return new DirtyRecord(playerId, merged, version);
        });
        return true;
    }

    /**
     * 当前待同步的脏记录数（测试与指标用）。
     */
    public int dirtySize() {
        return dirty.size();
    }

    /**
     * 从 PG 读取玩家行回填：INSERT ... ON CONFLICT DO NOTHING 后 SELECT。
     */
    public Map<String, String> loadOrCreate(Long playerId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, online, last_login_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 0, NOW(), NOW(), 1) ON CONFLICT (id) DO NOTHING",
                playerId, "player-" + playerId, "");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, name, email, online, last_login_at, updated_at, version FROM users WHERE id = ?",
                playerId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", String.valueOf(row.get("id")));
        fields.put("name", row.get("name") == null ? "player-" + playerId : String.valueOf(row.get("name")));
        fields.put("email", row.get("email") == null ? "" : String.valueOf(row.get("email")));
        fields.put("online", String.valueOf(row.get("online") == null ? 0 : row.get("online")));
        fields.put("last_login_at", timestampString(row.get("last_login_at")));
        fields.put("updated_at", timestampString(row.get("updated_at")));
        fields.put("version", String.valueOf(row.get("version") == null ? 1 : row.get("version")));
        return fields;
    }

    /**
     * 定时批量 UPSERT：按 flushConcurrency 分片并行执行，成功移除脏记录，失败保留重试。
     * 未 start() 时（如单元测试）同步执行。
     */
    public void flush() {
        if (dirty.isEmpty()) {
            return;
        }
        long startNanos = System.nanoTime();
        List<DirtyRecord> snapshot = new ArrayList<>(dirty.values());
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
        LogicMetrics metrics = LogicContext.getLogicMetricsOrNull();
        if (metrics != null) {
            metrics.recordFlush(System.nanoTime() - startNanos);
        }
    }

    private void flushShard(List<DirtyRecord> shard) {
        try {
            int[] results = jdbcTemplate.batchUpdate(PlayerUpsertSql.SQL, PlayerUpsertSql.batchParams(shard));
            for (int i = 0; i < shard.size(); i++) {
                DirtyRecord r = shard.get(i);
                // results[i] > 0 表示插入或更新成功；== 0 表示乐观锁冲突（WHERE version 不匹配），保留重试
                if (results[i] > 0) {
                    dirty.computeIfPresent(r.playerId(), (k, cur) -> cur.version() == r.version() ? null : cur);
                }
            }
        } catch (Exception e) {
            log.error("flush shard failed, {} records retained for retry", shard.size(), e);
            LogicMetrics metrics = LogicContext.getLogicMetricsOrNull();
            if (metrics != null) {
                metrics.recordFlushFailure();
            }
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

    private static String timestampString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp timestamp) {
            return String.valueOf(timestamp.getTime());
        }
        if (value instanceof java.util.Date date) {
            return String.valueOf(date.getTime());
        }
        return String.valueOf(value);
    }
}
