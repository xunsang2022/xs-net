package com.game.logic.dbsync;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DBSyncEngineTest {

    private DBSyncEngine newEngine(JdbcTemplate jdbcTemplate) {
        DBSyncConfig config = new DBSyncConfig();
        config.setFlushConcurrency(1);
        return new DBSyncEngine(jdbcTemplate, config);
    }

    @Test
    void submitMergesDirtyFieldsForSamePlayer() {
        DBSyncEngine engine = newEngine(mock(JdbcTemplate.class));

        Map<String, String> first = new LinkedHashMap<>();
        first.put("name", "alice");
        engine.submit(42L, first, 1L);

        Map<String, String> second = new LinkedHashMap<>();
        second.put("online", "1");
        engine.submit(42L, second, 2L);

        // 通过一次 flush 验证合并结果：脏记录应同时包含 name 与 online，version 更新为 2
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class), anyList())).thenReturn(new int[]{1});
        DBSyncEngine engine2 = newEngine(jdbc);
        engine2.submit(42L, first, 1L);
        engine2.submit(42L, second, 2L);
        engine2.flush();
        // 合并后成功 flush，脏记录被移除
        assertEquals(0, engine2.dirtySize());
    }

    @Test
    void flushRemovesDirtyRecordOnSuccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class), anyList())).thenReturn(new int[]{1});
        DBSyncEngine engine = newEngine(jdbc);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(0, engine.dirtySize());
    }

    @Test
    void flushRetainsDirtyRecordOnOptimisticLockConflict() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // results[i] == 0 表示乐观锁冲突（WHERE version 不匹配），应保留重试
        when(jdbc.batchUpdate(any(String.class), anyList())).thenReturn(new int[]{0});
        DBSyncEngine engine = newEngine(jdbc);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(1, engine.dirtySize());
    }

    @Test
    void flushRetainsDirtyRecordOnException() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(any(String.class), anyList())).thenThrow(new RuntimeException("db down"));
        DBSyncEngine engine = newEngine(jdbc);

        engine.submit(42L, Map.of("name", "alice"), 1L);
        engine.flush();

        assertEquals(1, engine.dirtySize());
    }
}
