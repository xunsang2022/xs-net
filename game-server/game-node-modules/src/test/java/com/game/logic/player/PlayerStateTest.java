package com.game.logic.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStateTest {

    @Test
    void markDirtyTracksFieldsInOrderAndDeduplicates() {
        PlayerState state = new PlayerState();
        state.markDirty(PlayerState.FIELD_NAME);
        state.markDirty(PlayerState.FIELD_VERSION);
        state.markDirty(PlayerState.FIELD_NAME);

        assertEquals(List.of(PlayerState.FIELD_NAME, PlayerState.FIELD_VERSION), state.getDirtyFields());
        assertTrue(state.hasDirtyFields());

        state.clearDirty();
        assertFalse(state.hasDirtyFields());
        assertTrue(state.getDirtyFields().isEmpty());
    }

    @Test
    void markDirtyRejectsUnknownField() {
        PlayerState state = new PlayerState();
        assertThrows(IllegalArgumentException.class, () -> state.markDirty("unknown"));
    }

    @Test
    void hashRoundTripPreservesFields() {
        PlayerState state = new PlayerState();
        state.setId(42L);
        state.setName("alice");
        state.setEmail("alice@example.com");
        state.setOnline(true);
        state.setLastLoginAt(1700000000000L);
        state.setUpdatedAt(1700000001000L);
        state.setVersion(7L);

        PlayerState restored = new PlayerState();
        restored.fromHash(state.toHash());
        assertEquals(42L, restored.getId());
        assertEquals("alice", restored.getName());
        assertEquals("alice@example.com", restored.getEmail());
        assertTrue(restored.isOnline());
        assertEquals(1700000000000L, restored.getLastLoginAt());
        assertEquals(1700000001000L, restored.getUpdatedAt());
        assertEquals(7L, restored.getVersion());
    }

    @Test
    void toHashOnlyIncludesRequestedFields() {
        PlayerState state = new PlayerState();
        state.setId(42L);
        state.setName("alice");
        state.setOnline(true);

        Map<String, String> partial = state.toHash(List.of(PlayerState.FIELD_NAME, PlayerState.FIELD_ONLINE));
        assertEquals(2, partial.size());
        assertEquals("alice", partial.get(PlayerState.FIELD_NAME));
        assertEquals("1", partial.get(PlayerState.FIELD_ONLINE));
        assertNull(partial.get(PlayerState.FIELD_ID));
    }

    @Test
    void fieldValueRejectsUnknownField() {
        PlayerState state = new PlayerState();
        assertThrows(IllegalArgumentException.class, () -> state.fieldValue("unknown"));
    }

    @Test
    void clearDirtyMatchingKeepsFieldsChangedWhileIoWasInFlight() {
        PlayerState state = new PlayerState();
        state.setName("before");
        state.setOnline(true);
        state.markDirty(PlayerState.FIELD_NAME);
        state.markDirty(PlayerState.FIELD_ONLINE);

        Map<String, String> persisted = state.toHash(state.getDirtyFields());
        state.setName("after");
        state.clearDirtyMatching(persisted);

        assertEquals(List.of(PlayerState.FIELD_NAME), state.getDirtyFields());
    }
}
