package com.game.logic.actor.player.core;

import com.game.logic.actor.player.message.RedisFlushResult;
import com.game.logic.actor.player.message.DestroyResult;
import com.game.eventsourcing.DBSyncEngine;
import com.game.logic.context.LogicContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerStateSyncTest {

    @AfterEach
    void clearContext() {
        LogicContext.setDbSyncEngine(null);
    }

    @Test
    void successfulRedisFlushIsSubmittedToDatabaseSync() {
        DBSyncEngine engine = mock(DBSyncEngine.class);
        LogicContext.setDbSyncEngine(engine);
        PlayerStateSync sync = new PlayerStateSync("42");

        Map<String, String> dirty = Map.of("online", "1");
        Map<String, String> snapshot = Map.of("online", "1", "version", "3");
        when(engine.submit(42L, snapshot, 3L)).thenReturn(true);
        boolean accepted = sync.completeFlush(new RedisFlushResult(dirty, snapshot, 3L, true, null));

        assertTrue(accepted);
        verify(engine).submit(eq(42L), eq(snapshot), eq(3L));
    }

    @Test
    void databaseSyncBackpressureKeepsRedisFlushDirty() {
        DBSyncEngine engine = mock(DBSyncEngine.class);
        when(engine.submit(42L, Map.of("online", "1"), 3L)).thenReturn(false);
        LogicContext.setDbSyncEngine(engine);
        PlayerStateSync sync = new PlayerStateSync("42");

        boolean accepted = sync.completeFlush(new RedisFlushResult(
                Map.of("online", "1"), Map.of("online", "1"), 3L, true, null));

        assertFalse(accepted);
    }

    @Test
    void successfulDestroyCompletionDoesNotRequireDirtyQueueCapacity() {
        Map<String, String> snapshot = Map.of("online", "0", "version", "4");
        PlayerStateSync sync = new PlayerStateSync("42");

        boolean completed = sync.completeDestroy(new DestroyResult(snapshot, 4L, true, null));

        assertTrue(completed);
    }
}
