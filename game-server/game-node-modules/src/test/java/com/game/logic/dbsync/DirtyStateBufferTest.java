package com.game.logic.dbsync;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirtyStateBufferTest {
    @Test
    void coalescesLatestFieldsForSamePlayer() {
        DirtyStateBuffer buffer = new DirtyStateBuffer(2, 4096);

        assertEquals(DirtyStateBuffer.SubmitResult.ACCEPTED,
                buffer.submit(1L, Map.of("name", "alice"), 1));
        assertEquals(DirtyStateBuffer.SubmitResult.COALESCED,
                buffer.submit(1L, Map.of("online", "1"), 2));

        DirtyRecord record = buffer.snapshot(1).getFirst();
        assertEquals(Map.of("name", "alice", "online", "1"), record.fields());
        assertEquals(2, record.version());
    }

    @Test
    void rejectsNewPlayersAtRecordCapacity() {
        DirtyStateBuffer buffer = new DirtyStateBuffer(1, 4096);
        buffer.submit(1L, Map.of("online", "1"), 1);
        assertEquals(DirtyStateBuffer.SubmitResult.REJECTED_CAPACITY,
                buffer.submit(2L, Map.of("online", "1"), 1));
    }
}
