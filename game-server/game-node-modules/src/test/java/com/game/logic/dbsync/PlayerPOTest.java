package com.game.logic.dbsync;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerPOTest {

    @Test
    void mapsDirtyRecordToActiveRecordWithOptimisticLockVersions() {
        DirtyRecord record = new DirtyRecord(
                42L,
                Map.of("name", "alice", "version", "7", "online", "1"),
                6L,
                7L);

        PlayerPO player = PlayerPO.fromDirtyRecord(record);

        assertEquals(42L, player.getId());
        assertEquals("alice", player.getName());
        assertEquals(1, player.getOnline());
        assertEquals(6, player.getExpectedVersion());
        assertEquals(7, player.getVersion());
    }
}
