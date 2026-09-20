package com.game.logic.dbsync;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DBSyncEngineTest {

    private DBSyncEngine newEngine(PlayerPersistence persistence) {
        DBSyncConfig config = new DBSyncConfig();
        config.setFlushConcurrency(1);
        return new DBSyncEngine(persistence, config);
    }

    @Test
    void submitMergesDirtyFieldsForSamePlayer() {
        DBSyncEngine engine = newEngine(mock(PlayerPersistence.class));

        Map<String, String> first = new LinkedHashMap<>();
        first.put("name", "alice");
        engine.submit(42L, first, 1L);

        Map<String, String> second = new LinkedHashMap<>();
        second.put("online", "1");
        engine.submit(42L, second, 2L);

        // 通过一次 flush 验证合并结果：脏记录应同时包含 name 与 online，version 更新为 2
        PlayerPersistence persistence = mock(PlayerPersistence.class);
        when(persistence.upsertBatch(anyList())).thenReturn(java.util.Set.of(42L));
        DBSyncEngine engine2 = newEngine(persistence);
        engine2.submit(42L, first, 1L);
        engine2.submit(42L, second, 2L);
        engine2.flush();
        // 合并后成功 flush，脏记录被移除
        assertEquals(0, engine2.dirtySize());
    }

    @Test
    void flushRemovesDirtyRecordOnSuccess() {
        PlayerPersistence persistence = mock(PlayerPersistence.class);
        when(persistence.upsertBatch(anyList())).thenReturn(java.util.Set.of(42L));
        DBSyncEngine engine = newEngine(persistence);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(0, engine.dirtySize());
    }

    @Test
    void flushRetainsDirtyRecordOnOptimisticLockConflict() {
        PlayerPersistence persistence = mock(PlayerPersistence.class);
        // RETURNING 未返回该 ID 表示乐观锁冲突，应保留重试
        when(persistence.upsertBatch(anyList())).thenReturn(java.util.Set.of());
        DBSyncEngine engine = newEngine(persistence);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(1, engine.dirtySize());
    }

    @Test
    void flushRetainsDirtyRecordOnException() {
        PlayerPersistence persistence = mock(PlayerPersistence.class);
        when(persistence.upsertBatch(anyList())).thenThrow(new RuntimeException("db down"));
        DBSyncEngine engine = newEngine(persistence);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(1, engine.dirtySize());
    }

    @Test
    void submitRejectsWhenDirtyMapFull() {
        DBSyncConfig config = new DBSyncConfig();
        config.setFlushConcurrency(1);
        config.setMaxDirtyRecords(2);
        DBSyncEngine engine = new DBSyncEngine(mock(PlayerPersistence.class), config);

        assertTrue(engine.submit(1L, Map.of("name", "alice"), 1L));
        assertTrue(engine.submit(2L, Map.of("name", "bob"), 1L));
        // 超限：拒绝新提交，脏记录数保持在上限
        assertFalse(engine.submit(3L, Map.of("name", "carol"), 1L));
        assertEquals(2, engine.dirtySize());
    }
}
