package com.game.eventsourcing;

import com.game.eventsourcing.sync.DirtyRecord;
import com.game.eventsourcing.sync.DirtyStateBuffer;
import com.game.eventsourcing.sync.RecordKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyStateBufferTest {
    @Test
    void coalescesLatestFieldsForSameEntity() {
        DirtyStateBuffer buffer = new DirtyStateBuffer(2, 4096);

        assertEquals(DirtyStateBuffer.SubmitResult.ACCEPTED,
                buffer.submit("player", 1L, Map.of("name", "alice"), 1));
        assertEquals(DirtyStateBuffer.SubmitResult.COALESCED,
                buffer.submit("player", 1L, Map.of("online", "1"), 2));

        DirtyRecord record = buffer.snapshot(1).getFirst();
        assertEquals(Map.of("name", "alice", "online", "1"), record.fields());
        assertEquals(2, record.version());
    }

    @Test
    void rejectsNewEntitiesAtRecordCapacity() {
        DirtyStateBuffer buffer = new DirtyStateBuffer(1, 4096);
        buffer.submit("player", 1L, Map.of("online", "1"), 1);
        assertEquals(DirtyStateBuffer.SubmitResult.REJECTED_CAPACITY,
                buffer.submit("player", 2L, Map.of("online", "1"), 1));
    }

    @Test
    void keepsEntitiesWithSameIdSeparate() {
        DirtyStateBuffer buffer = new DirtyStateBuffer(4, 4096);
        buffer.submit("player", 1L, Map.of("name", "alice"), 1);
        buffer.submit("guild", 1L, Map.of("level", "3"), 1);

        List<DirtyRecord> snapshot = buffer.snapshot(10);
        assertEquals(2, buffer.size(), "同 ID 不同实体类型应各占一条脏记录");
        assertTrue(snapshot.stream().anyMatch(r -> r.entityType().equals("player") && r.entityId().equals(1L)));
        assertTrue(snapshot.stream().anyMatch(r -> r.entityType().equals("guild") && r.entityId().equals(1L)));

        buffer.removeIfVersion(new RecordKey("player", 1L), 1);
        List<DirtyRecord> remaining = buffer.snapshot(10);
        assertFalse(remaining.stream().anyMatch(r -> r.entityType().equals("player") && r.entityId().equals(1L)));
        assertTrue(remaining.stream().anyMatch(r -> r.entityType().equals("guild") && r.entityId().equals(1L)),
                "移除 player 不应影响 guild");
    }
}