package com.game.eventsourcing;

import com.game.eventsourcing.sync.DBSyncConfig;
import com.game.eventsourcing.sync.DBSyncEngine;
import com.game.eventsourcing.sync.DBSyncEngineRegistry;
import com.game.eventsourcing.sync.DirtyRecord;
import com.game.eventsourcing.sync.RecordKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 多实体引擎路由与隔离回归：
 *  - submit / loadOrCreate / saveNow 按 entityType 路由；
 *  - 未注册实体回落 default 共享引擎；
 *  - 独立引擎与共享引擎的容量完全隔离；
 *  - startAll / flushAll / stopAll 覆盖每个引擎一次。
 */
class DBSyncEngineRegistryTest {

    /** 内存引擎：成功写入全部记录，加载结果带引擎标记。 */
    private static DBSyncEngine newEngine(Map<RecordKey, DirtyRecord> pg, int maxDirtyRecords, String engineTag) {
        DBSyncConfig config = new DBSyncConfig();
        config.setBatchSize(10);
        config.setFlushConcurrency(1);
        config.setMaxDirtyRecords(maxDirtyRecords);
        config.setMaxDirtyBytes(1024 * 1024);
        DBSyncEngine.PersistenceCallback callback = new DBSyncEngine.PersistenceCallback() {
            @Override
            public RecordKey upsertRecord(DirtyRecord record) {
                pg.put(record.key(), record);
                return record.key();
            }

            @Override
            public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
                List<RecordKey> keys = new ArrayList<>(records.size());
                for (DirtyRecord record : records) {
                    pg.put(record.key(), record);
                    keys.add(record.key());
                }
                return keys;
            }
        };
        DBSyncEngine.LoadCallback load = (entityType, entityId) ->
                Map.of("engine", engineTag, "entityId", String.valueOf(entityId));
        return new DBSyncEngine(config, null, callback, load);
    }

    @Test
    void unregisteredEntityTypeFallsBackToDefaultEngine() {
        Map<RecordKey, DirtyRecord> pg = new LinkedHashMap<>();
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(newEngine(pg, 100, "default"), Map.of());

        assertTrue(registry.submit("player", 1L, Map.of("hp", "100"), 1L));
        assertTrue(registry.submit("item", 2L, Map.of("count", "3"), 1L));
        registry.flushAll();

        assertTrue(pg.containsKey(new RecordKey("player", 1L)));
        assertTrue(pg.containsKey(new RecordKey("item", 2L)));
        assertEquals("default", registry.loadOrCreate("item", 2L).get("engine"));
    }

    @Test
    void registeredEntityTypeRoutesToOverrideEngine() {
        Map<RecordKey, DirtyRecord> defaultPg = new LinkedHashMap<>();
        Map<RecordKey, DirtyRecord> guildPg = new LinkedHashMap<>();
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(
                newEngine(defaultPg, 100, "default"),
                Map.of("guild", newEngine(guildPg, 100, "guild")));

        assertTrue(registry.submit("guild", 1L, Map.of("level", "3"), 1L));
        registry.flushAll();

        assertTrue(guildPg.containsKey(new RecordKey("guild", 1L)), "guild 记录应落在独立引擎");
        assertTrue(defaultPg.isEmpty(), "共享引擎不应收到 guild 记录");
        assertEquals("guild", registry.loadOrCreate("guild", 1L).get("engine"));
    }

    @Test
    void sharedEngineQueueFullDoesNotAffectOverrideEngine() {
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(
                newEngine(new LinkedHashMap<>(), 1, "default"),
                Map.of("guild", newEngine(new LinkedHashMap<>(), 100, "guild")));

        assertTrue(registry.submit("player", 1L, Map.of("hp", "100"), 1L));
        assertFalse(registry.submit("player", 2L, Map.of("hp", "200"), 1L),
                "共享引擎队列满应拒绝新的玩家提交");

        assertTrue(registry.submit("guild", 1L, Map.of("level", "3"), 1L),
                "共享引擎队列满不应影响 guild 独立引擎");
    }

    @Test
    void saveNowRoutesByEntityType() {
        Map<RecordKey, DirtyRecord> guildPg = new LinkedHashMap<>();
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(
                newEngine(new LinkedHashMap<>(), 100, "default"),
                Map.of("guild", newEngine(guildPg, 100, "guild")));

        assertTrue(registry.saveNow("guild", 7L, Map.of("level", "5"), 2L));
        assertTrue(guildPg.containsKey(new RecordKey("guild", 7L)));
    }

    @Test
    void lifecycleCallsCoverEachEngineOnce() {
        DBSyncEngine shared = mock(DBSyncEngine.class);
        DBSyncEngine guild = mock(DBSyncEngine.class);
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(shared, Map.of("guild", guild));

        registry.startAll();
        registry.flushAll();
        registry.stopAll();

        verify(shared, times(1)).start();
        verify(shared, times(1)).flush();
        verify(shared, times(1)).stop();
        verify(guild, times(1)).start();
        verify(guild, times(1)).flush();
        verify(guild, times(1)).stop();
    }

    @Test
    void sameEngineInstanceIsOnlyStartedOnce() {
        DBSyncEngine shared = mock(DBSyncEngine.class);
        DBSyncEngineRegistry registry = new DBSyncEngineRegistry(shared, Map.of("player", shared));

        registry.startAll();
        registry.stopAll();

        verify(shared, times(1)).start();
        verify(shared, times(1)).stop();
    }
}