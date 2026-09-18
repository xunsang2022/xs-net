package com.game.logic.actor.player.core;

import com.game.message.c2s.PlayerEnterCommand;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLifecycleTest {

    @Test
    void playerMovesFromLoadingToOnlineAndThenDestroying() {
        PlayerLifecycle lifecycle = new PlayerLifecycle();
        PlayerEnterCommand command = new PlayerEnterCommand(42L, "alice", "channel-1");

        assertTrue(lifecycle.beginLoading(command));
        assertTrue(lifecycle.isLoading());
        assertFalse(lifecycle.beginLoading(command));

        Map<String, String> snapshot = lifecycle.prepareOnlineState(42L, Map.of("name", "alice"));
        assertEquals("42", snapshot.get("id"));
        assertEquals("1", snapshot.get("online"));

        lifecycle.markOnline();
        assertTrue(lifecycle.isOnline());
        assertTrue(lifecycle.beginDestroyLifecycle());
        assertFalse(lifecycle.isOnline());
        assertTrue(lifecycle.isDestroying());
        assertTrue(lifecycle.shouldPersistOnStop());

        lifecycle.markDestroyed();
        assertTrue(lifecycle.isStopping());
        assertFalse(lifecycle.shouldPersistOnStop());
    }

    @Test
    void failedLoadingDoesNotRequestStopPersistence() {
        PlayerLifecycle lifecycle = new PlayerLifecycle();

        assertTrue(lifecycle.beginLoading(new PlayerEnterCommand(42L, "alice", "channel-1")));
        lifecycle.failLoading();

        assertFalse(lifecycle.isLoading());
        assertFalse(lifecycle.isOnline());
        assertFalse(lifecycle.shouldPersistOnStop());
    }
}
