package com.game.eventsourcing;

import com.game.eventsourcing.sync.DirtyRecord;
import com.game.eventsourcing.sync.EntityMapper;
import com.game.eventsourcing.sync.EntityPersistenceRouter;
import com.game.eventsourcing.sync.RecordKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享引擎内多实体分发回归：EntityPersistenceRouter 按 entityType 把脏记录分组路由到对应 EntityMapper，
 * 并合并各实体的成功写入键；未注册实体保留重试，不拖累其它实体。
 */
class EntityPersistenceRouterTest {

    /** 内存版实体 mapper：记录收到的批次，全部视为成功写入。 */
    private static final class FakeMapper implements EntityMapper {
        private final String type;
        final List<DirtyRecord> batchCalls = new ArrayList<>();
        final Map<Long, DirtyRecord> stored = new LinkedHashMap<>();

        FakeMapper(String type) {
            this.type = type;
        }

        @Override
        public String entityType() {
            return type;
        }

        @Override
        public Long upsertRecord(DirtyRecord record) {
            stored.put(record.entityId(), record);
            return record.entityId();
        }

        @Override
        public List<Long> upsertBatch(List<DirtyRecord> records) {
            batchCalls.addAll(records);
            List<Long> ids = new ArrayList<>(records.size());
            for (DirtyRecord record : records) {
                stored.put(record.entityId(), record);
                ids.add(record.entityId());
            }
            return ids;
        }

        @Override
        public Map<String, String> loadOrCreate(Long entityId) {
            return Map.of("id", String.valueOf(entityId), "type", type);
        }
    }

    @Test
    void routesBatchByEntityTypeAndMergesPersistedKeys() {
        FakeMapper player = new FakeMapper("player");
        FakeMapper item = new FakeMapper("item");
        EntityPersistenceRouter router = new EntityPersistenceRouter(List.of(player, item));

        DirtyRecord p1 = new DirtyRecord("player", 1L, Map.of("hp", "100"), 0, 1);
        DirtyRecord i1 = new DirtyRecord("item", 1L, Map.of("count", "5"), 0, 1);
        DirtyRecord p2 = new DirtyRecord("player", 2L, Map.of("hp", "20"), 0, 1);

        List<RecordKey> persisted = router.upsertBatch(List.of(p1, i1, p2));

        assertEquals(List.of(p1, p2), player.batchCalls, "player 应只收到自己的记录");
        assertEquals(List.of(i1), item.batchCalls, "item 应只收到自己的记录");
        assertEquals(Set.of(new RecordKey("player", 1L), new RecordKey("item", 1L), new RecordKey("player", 2L)),
                Set.copyOf(persisted), "成功键应合并返回并带 entityType");
    }

    @Test
    void unregisteredEntityTypeIsRetainedWithoutFailingOthers() {
        FakeMapper player = new FakeMapper("player");
        EntityPersistenceRouter router = new EntityPersistenceRouter(List.of(player));

        DirtyRecord p1 = new DirtyRecord("player", 1L, Map.of("hp", "100"), 0, 1);
        DirtyRecord g1 = new DirtyRecord("guild", 1L, Map.of("level", "3"), 0, 1);

        List<RecordKey> persisted = router.upsertBatch(List.of(p1, g1));

        assertEquals(List.of(new RecordKey("player", 1L)), persisted,
                "未注册实体不得出现在成功键中（保留重试），已注册实体正常写入");
    }

    @Test
    void upsertRecordWrapsEntityKey() {
        FakeMapper player = new FakeMapper("player");
        EntityPersistenceRouter router = new EntityPersistenceRouter(List.of(player));

        RecordKey key = router.upsertRecord(new DirtyRecord("player", 7L, Map.of("hp", "1"), 0, 1));

        assertEquals(new RecordKey("player", 7L), key);
        assertTrue(player.stored.containsKey(7L));
    }

    @Test
    void loadOrCreateDispatchesByEntityType() {
        FakeMapper player = new FakeMapper("player");
        EntityPersistenceRouter router = new EntityPersistenceRouter(List.of(player));

        assertEquals(Map.of("id", "7", "type", "player"), router.loadOrCreate("player", 7L));
        assertThrows(IllegalArgumentException.class, () -> router.loadOrCreate("guild", 7L),
                "未注册实体的加载应快速失败");
    }

    @Test
    void duplicateEntityTypeRegistrationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EntityPersistenceRouter(
                List.of(new FakeMapper("player"), new FakeMapper("player"))));
    }
}