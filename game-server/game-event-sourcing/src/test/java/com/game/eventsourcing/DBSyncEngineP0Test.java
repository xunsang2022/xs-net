package com.game.eventsourcing;

import com.game.eventsourcing.sync.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P0 三项修复的回归测试：
 *  P0-1 saveNow 成功后清理 dirty / retry / terminal 状态
 *  P0-2 reclaimTerminalFailures 无条件原子删除
 *  P0-3 flushShard 对 persistedKeys 做交集防御 + Set 查询
 *
 * 该类同时作为 P1 测试的基类，所有需要被子类复用的成员均为 protected。
 *
 * 注意：P1-1 重构后 DBSyncEngine 的 retry 状态已合并为单个 retryStates map，
 * 因此本类不再有 retryAttempts / nextRetryNanos 辅助方法，统一用 retryStates。
 * 多实体改造后脏记录以 RecordKey(entityType, entityId) 为键，测试统一使用 "player" 实体。
 */
class DBSyncEngineP0Test {

    protected static final String ENTITY_TYPE = "player";

    protected DBSyncConfig config;
    protected SyncMetrics metrics;
    protected DBSyncEngine.PersistenceCallback persistence;
    protected DBSyncEngine.LoadCallback load;

    /** 内存版 PG：RecordKey -> 最近一次成功写入的 DirtyRecord */
    protected Map<RecordKey, DirtyRecord> fakePg;

    @BeforeEach
    void setUp() {
        config = new DBSyncConfig();
        config.setBatchSize(3);
        config.setFlushConcurrency(2);
        config.setMaxDirtyRecords(100);
        config.setMaxDirtyBytes(1024 * 1024);
        config.setRetryBackoffMs(1L);
        config.setMaxRetryBackoffMs(2L);
        config.setQuarantineRetentionMs(0L);
        config.setMaxRetryAttempts(1);

        metrics = mock(SyncMetrics.class);
        fakePg = new ConcurrentHashMap<>();

        persistence = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                fakePg.put(record.key(), record);
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                List<RecordKey> ok = new ArrayList<>(records.size());
                for (DirtyRecord r : records) {
                    fakePg.put(r.key(), r);
                    ok.add(r.key());
                }
                return ok;
            }
        };

        load = (entityType, entityId) -> new LinkedHashMap<>();
    }

    @AfterEach
    void tearDown() {
        // 本测试不 start 引擎，无资源需释放
    }

    protected DBSyncEngine newEngine() {
        return new DBSyncEngine(config, metrics, persistence, load);
    }

    /** 构造 "player" 实体键，测试统一使用该实体。 */
    protected static RecordKey playerKey(Long entityId) {
        return new RecordKey(ENTITY_TYPE, entityId);
    }

    // ------------------------------------------------------------------
    // P0-1: saveNow 清理
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-1: saveNow 成功后 dirty 中不再有该实体键的脏记录")
    void saveNow_clearsDirtyRecord() throws Exception {
        DBSyncEngine engine = newEngine();

        assertTrue(engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L));
        assertEquals(1, dirtySize(engine));

        boolean saved = engine.saveNow(ENTITY_TYPE, 1L, Map.of("hp", "80", "mp", "50"), 2L);
        assertTrue(saved);

        assertEquals(0, dirtySize(engine), "saveNow 后 dirty 应被清空");

        engine.flush();
        assertEquals(2L, fakePg.get(playerKey(1L)).version(), "PG 中应是 saveNow 的版本");
    }

    @Test
    @DisplayName("P0-1: saveNow 失败（返回 null）时不清 dirty，保留待重试")
    void saveNow_failure_keepsDirty() throws Exception {
        DBSyncEngine.PersistenceCallback failing = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                return null;
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                return List.of();
            }
        };
        DBSyncEngine engine = new DBSyncEngine(config, metrics, failing, load);
        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);

        assertFalse(engine.saveNow(ENTITY_TYPE, 1L, Map.of("hp", "80"), 2L));
        assertEquals(1, dirtySize(engine), "saveNow 失败后 dirty 应保留");
    }

    @Test
    @DisplayName("P0-1: saveNow 清理 retry/terminal 状态，后续 submit 不被旧状态拖累")
    void saveNow_clearsRetryAndTerminal() throws Exception {
        DBSyncEngine engine = newEngine();
        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);

        // 手动把实体键 1 标记为终态（模拟重试耗尽）
        markTerminal(engine, playerKey(1L), 1);
        assertTrue(terminalFailures(engine).contains(playerKey(1L)));

        engine.saveNow(ENTITY_TYPE, 1L, Map.of("hp", "80"), 2L);

        assertFalse(terminalFailures(engine).contains(playerKey(1L)), "saveNow 应清理终态标记");
        assertFalse(retryStates(engine).containsKey(playerKey(1L)), "saveNow 应清理重试状态");
    }

    // ------------------------------------------------------------------
    // P0-2: reclaimTerminalFailures 无条件原子删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-2: 终态回收即使版本已变化也能删除 dirty 记录")
    void reclaimTerminal_removesEvenIfVersionChanged() throws Exception {
        DBSyncEngine engine = newEngine();

        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);
        markTerminal(engine, playerKey(1L), config.getMaxRetryAttempts());
        replaceDirtyVersion(engine, playerKey(1L), 999L);
        assertNotNull(getDirtyRecord(engine, playerKey(1L)), "dirty 中应仍有一条记录");

        engine.flush();

        assertNull(getDirtyRecord(engine, playerKey(1L)),
                "终态回收应无条件删除 dirty 记录，不因版本变化而残留");
        assertFalse(terminalFailures(engine).contains(playerKey(1L)));
    }

    @Test
    @DisplayName("P0-2: 终态回收后不再被 flush 重试")
    void reclaimTerminal_notRetriedAfterReclaim() throws Exception {
        DBSyncEngine engine = newEngine();

        AtomicInteger upsertCalls = new AtomicInteger();
        DBSyncEngine.PersistenceCallback counting = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                upsertCalls.incrementAndGet();
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                upsertCalls.addAndGet(records.size());
                return records.stream().map(DirtyRecord::key).toList();
            }
        };
        DBSyncEngine eng = new DBSyncEngine(config, metrics, counting, load);

        eng.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);
        markTerminal(eng, playerKey(1L), config.getMaxRetryAttempts());

        eng.flush();
        assertEquals(0, upsertCalls.get(), "终态回收后不应再 UPSERT");

        eng.flush();
        assertEquals(0, upsertCalls.get(), "后续 flush 也不应写入已回收记录");
    }

    // ------------------------------------------------------------------
    // P0-3: persistedKeys 交集防御 + Set 查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P0-3: callback 返回 batch 外的实体键时不被误删")
    void flushShard_ignoresPersistedIdsOutsideBatch() throws Exception {
        DBSyncEngine.PersistenceCallback malicious = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                List<RecordKey> result = new ArrayList<>();
                records.forEach(r -> result.add(r.key()));
                result.add(new RecordKey(ENTITY_TYPE, 999L));
                return result;
            }
        };
        DBSyncEngine engine = new DBSyncEngine(config, metrics, malicious, load);

        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);
        engine.submit(ENTITY_TYPE, 2L, Map.of("hp", "200"), 1L);

        engine.flush();

        assertNull(getDirtyRecord(engine, playerKey(1L)));
        assertNull(getDirtyRecord(engine, playerKey(2L)));
        assertNull(getDirtyRecord(engine, playerKey(999L)));
    }

    @Test
    @DisplayName("P0-3: callback 返回 null / 空集合时记录保留待重试")
    void flushShard_nullOrEmptyPersistedIds_schedulesRetry() throws Exception {
        DBSyncEngine.PersistenceCallback nullReturning = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                return null;
            }
        };
        DBSyncEngine engine = new DBSyncEngine(config, metrics, nullReturning, load);
        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);

        engine.flush();

        assertNotNull(getDirtyRecord(engine, playerKey(1L)), "返回 null 时记录应保留");
        assertTrue(retryStates(engine).containsKey(playerKey(1L)),
                "失败后应在 retryStates 中登记重试");
    }

    @Test
    @DisplayName("P0-3: 部分成功时，成功的移除、失败的排重试")
    void flushShard_partialSuccess() throws Exception {
        DBSyncEngine.PersistenceCallback partial = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                List<RecordKey> ok = new ArrayList<>();
                for (DirtyRecord r : records) {
                    if (r.entityId() == 1L) {
                        ok.add(r.key());
                    }
                }
                return ok;
            }
        };
        DBSyncEngine engine = new DBSyncEngine(config, metrics, partial, load);
        engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);
        engine.submit(ENTITY_TYPE, 2L, Map.of("hp", "200"), 1L);

        engine.flush();

        assertNull(getDirtyRecord(engine, playerKey(1L)), "entityId=1 应已移除");
        assertNotNull(getDirtyRecord(engine, playerKey(2L)), "entityId=2 应保留待重试");
        assertTrue(retryStates(engine).containsKey(playerKey(2L)), "entityId=2 应排重试");
    }

    @Test
    @DisplayName("P0-3 附带: stop 与 flush 并发时任务不静默丢失")
    void stopConcurrentFlush_noTaskLost() throws Exception {
        config.setFlushConcurrency(2);
        config.setBatchSize(1);
        config.setMaxDirtyRecords(10_000);
        config.setMaxDirtyBytes(64L * 1024 * 1024);
        DBSyncEngine engine = newEngine();
        engine.start();

        int players = 200;
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        List<Long> submitted = new ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (long i = 1; i <= players; i++) {
            final long id = i;
            submitted.add(id);
            submitters.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                if (!engine.submit(ENTITY_TYPE, id, Map.of("hp", String.valueOf(id)), 1L)) {
                    rejected.incrementAndGet();
                }
            });
        }
        start.countDown();
        submitters.shutdown();
        assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS));

        Thread stopper = new Thread(engine::stop);
        stopper.start();
        engine.flush();
        stopper.join(5000);

        for (Long id : submitted) {
            if (rejected.get() > 0 && !fakePg.containsKey(playerKey(id)) && getDirtyRecord(engine, playerKey(id)) == null) {
                continue;
            }
            boolean ok = fakePg.containsKey(playerKey(id)) || getDirtyRecord(engine, playerKey(id)) != null;
            assertTrue(ok, "entityId " + id + " 既不在 PG 也不在 dirty，任务丢失");
        }
        System.out.printf("stopConcurrentFlush: submitted=%d, rejected=%d, inPg=%d, inDirty=%d%n",
                players, rejected.get(), fakePg.size(), dirtySize(engine));
    }

    // ==================================================================
    // 反射辅助：访问私有字段做白盒断言（全部 protected，供子类复用）
    // ==================================================================

    @SuppressWarnings("unchecked")
    protected static <T> T field(DBSyncEngine engine, String name) throws Exception {
        Field f = DBSyncEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(engine);
    }

    protected static int dirtySize(DBSyncEngine engine) throws Exception {
        DirtyStateBuffer buf = field(engine, "dirty");
        return buf.size();
    }

    protected static DirtyRecord getDirtyRecord(DBSyncEngine engine, RecordKey key) throws Exception {
        DirtyStateBuffer buf = field(engine, "dirty");
        return buf.get(key);
    }

    @SuppressWarnings("unchecked")
    protected static void replaceDirtyVersion(DBSyncEngine engine, RecordKey key, long newVersion) throws Exception {
        DirtyStateBuffer buf = field(engine, "dirty");
        DirtyRecord cur = buf.get(key);
        assertNotNull(cur);
        buf.remove(key);
        DirtyRecord replaced = new DirtyRecord(
                key.entityType(), key.entityId(), cur.fields(), cur.expectedVersion(), newVersion);
        Field recordsField = DirtyStateBuffer.class.getDeclaredField("records");
        recordsField.setAccessible(true);
        ConcurrentHashMap<RecordKey, DirtyRecord> map =
                (ConcurrentHashMap<RecordKey, DirtyRecord>) recordsField.get(buf);
        map.put(key, replaced);
    }

    protected static void markTerminal(DBSyncEngine engine, RecordKey key, int attempts) throws Exception {
        java.lang.reflect.Method m =
                DBSyncEngine.class.getDeclaredMethod("markTerminal", RecordKey.class, int.class);
        m.setAccessible(true);
        m.invoke(engine, key, attempts);
    }

    @SuppressWarnings("unchecked")
    protected static Set<RecordKey> terminalFailures(DBSyncEngine engine) throws Exception {
        return field(engine, "terminalFailures");
    }

    /**
     * P1-1 重构后 DBSyncEngine 使用单个 retryStates map。
     * 旧的 retryAttempts / nextRetryNanos 字段已不存在，统一走这里。
     */
    @SuppressWarnings("unchecked")
    protected static Map<RecordKey, Object> retryStates(DBSyncEngine engine) throws Exception {
        return field(engine, "retryStates");
    }

    /**
     * 读取 dirty 缓冲区的当前字节占用。
     * 若 DirtyStateBuffer 内部字段名不同，请按实际实现调整。
     */
    protected static long engineBytes(DBSyncEngine engine) throws Exception {
        DirtyStateBuffer buf = field(engine, "dirty");
        Field bytesField = DirtyStateBuffer.class.getDeclaredField("bytes");
        bytesField.setAccessible(true);
        Object v = bytesField.get(buf);
        if (v instanceof AtomicLong al) {
            return al.get();
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        throw new IllegalStateException("Unsupported bytes field type: " + v.getClass());
    }
}