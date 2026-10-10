package com.game.eventsourcing;

import com.game.eventsourcing.sync.DBSyncEngine;
import com.game.eventsourcing.sync.DirtyRecord;
import com.game.eventsourcing.sync.RecordKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P1 三项修复的回归测试：
 *  P1-1 submit 与 scheduleRetry 的竞争（retryStates 单 map + compute）
 *  P1-2 合并分支容量检查
 *  P1-3 容量拒绝指标
 *
 * 继承 DBSyncEngineP0Test 的 setUp / tearDown / newEngine / 反射辅助，避免重复。
 */
class DBSyncEngineP1Test extends DBSyncEngineP0Test {

    // ------------------------------------------------------------------
    // P1-2: 合并分支容量检查
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1-2: 合并分支容量检查——字段增长超过 maxBytes 时拒绝")
    void submit_coalesceRejectsWhenBytesExceeded() throws Exception {
        config.setMaxDirtyBytes(200);
        DBSyncEngine engine = newEngine();

        assertTrue(engine.submit(ENTITY_TYPE, 1L, Map.of("a", "1"), 1L));

        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            big.put("key" + i, "value" + i);
        }
        assertFalse(engine.submit(ENTITY_TYPE, 1L, big, 2L), "合并后超 maxBytes 应被拒绝");

        DirtyRecord after = getDirtyRecord(engine, playerKey(1L));
        assertNotNull(after);
        assertEquals(1L, after.version(), "拒绝后原记录版本不应变化");
        assertFalse(after.fields().containsKey("key0"), "拒绝后字段不应被合并");
    }

    @Test
    @DisplayName("P1-2: 合并分支容量检查——字段覆盖使字节减少时不拒绝")
    void submit_coalesceAcceptsWhenBytesShrink() throws Exception {
        DBSyncEngine engine = newEngine();
        String bigValue = "x".repeat(1000);
        assertTrue(engine.submit(ENTITY_TYPE, 1L, Map.of("a", bigValue), 1L));
        long bytesAfterFirst = engineBytes(engine);

        assertTrue(engine.submit(ENTITY_TYPE, 1L, Map.of("a", "1"), 2L));
        long bytesAfterSecond = engineBytes(engine);

        assertTrue(bytesAfterSecond < bytesAfterFirst,
                "覆盖大 value 后字节数应减少：before=" + bytesAfterFirst + ", after=" + bytesAfterSecond);
    }

    // ------------------------------------------------------------------
    // P1-1: submit 重置重试状态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1-1: submit 重置重试状态，不被旧计数拖累")
    void submit_resetsRetryState() throws Exception {
        config.setMaxRetryAttempts(100);

        DBSyncEngine.PersistenceCallback failing = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord r) {
                return r.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                return List.of();
            }
        };

        DBSyncEngine eng = new DBSyncEngine(config, metrics, failing, load);
        eng.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L);
        eng.flush();
        assertTrue(retryStates(eng).containsKey(playerKey(1L)), "失败后应有重试状态");

        assertTrue(eng.submit(ENTITY_TYPE, 1L, Map.of("hp", "90"), 2L));
        assertFalse(retryStates(eng).containsKey(playerKey(1L)), "submit 应重置重试状态");
    }

    @Test
    @DisplayName("P1-1: 并发 submit 与 scheduleRetry 不产生不一致的 retryState")
    void submit_concurrentWithScheduleRetry_noInconsistency() throws Exception {
        config.setMaxRetryAttempts(100);

        DBSyncEngine.PersistenceCallback flaky = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord r) {
                return r.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                return records.stream()
                        .filter(r -> r.entityId() % 2 == 0)
                        .map(DirtyRecord::key)
                        .toList();
            }
        };
        DBSyncEngine eng = new DBSyncEngine(config, metrics, flaky, load);

        Thread submitter = new Thread(() -> {
            for (long i = 1; i <= 50; i++) {
                eng.submit(ENTITY_TYPE, i, Map.of("hp", String.valueOf(i)), 1L);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                }
            }
        });
        Thread flusher = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                eng.flush();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {
                }
            }
        });
        submitter.start();
        flusher.start();
        submitter.join(5000);
        flusher.join(5000);

        for (Map.Entry<RecordKey, Object> e : retryStates(eng).entrySet()) {
            int attempts = retryStateAttempts(e.getValue());
            assertTrue(attempts >= 1,
                    "retryState attempts 必须 >= 1，key=" + e.getKey() + ", attempts=" + attempts);
        }
    }

    // ------------------------------------------------------------------
    // P1-3: 容量拒绝指标
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P1-3: 容量拒绝时调用 recordSubmitRejected 指标")
    void submit_capacityRejected_recordsMetric() throws Exception {
        config.setMaxDirtyRecords(1);
        DBSyncEngine engine = newEngine();

        assertTrue(engine.submit(ENTITY_TYPE, 1L, Map.of("hp", "100"), 1L));
        assertFalse(engine.submit(ENTITY_TYPE, 2L, Map.of("hp", "200"), 1L));

        verify(metrics, times(1)).recordSubmitRejected();
    }

    @Test
    @DisplayName("P1-3: 合并分支容量拒绝也调 recordSubmitRejected")
    void submit_coalesceRejected_recordsMetric() throws Exception {
        config.setMaxDirtyBytes(200);
        DBSyncEngine engine = newEngine();
        engine.submit(ENTITY_TYPE, 1L, Map.of("a", "1"), 1L);

        Map<String, String> big = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            big.put("key" + i, "value" + i);
        }
        assertFalse(engine.submit(ENTITY_TYPE, 1L, big, 2L));

        verify(metrics, times(1)).recordSubmitRejected();
    }

    // ------------------------------------------------------------------
    // P1 辅助：读 RetryState.attempts（private record，反射）
    // ------------------------------------------------------------------

    private static int retryStateAttempts(Object retryState) throws Exception {
        Field f = retryState.getClass().getDeclaredField("attempts");
        f.setAccessible(true);
        return (int) f.get(retryState);
    }
}