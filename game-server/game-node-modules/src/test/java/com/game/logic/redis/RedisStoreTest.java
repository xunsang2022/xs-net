package com.game.logic.redis;

import com.game.logic.player.PlayerState;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisStoreTest {

    @Test
    void playerKeyUsesPlayerPrefix() {
        assertEquals("player:42", RedisStore.playerKey("42"));
    }

    @Test
    void scriptsCoverAtomicHashAndSetOperations() {
        assertTrue(RedisStore.FLUSH_DIRTY_LUA.contains("HSET"));
        assertTrue(RedisStore.FLUSH_DIRTY_LUA.contains("SADD"));
        assertTrue(RedisStore.DESTROY_LUA.contains("HSET"));
        assertTrue(RedisStore.DESTROY_LUA.contains("SREM"));
    }

    @Test
    void flushDirtyExecutesScriptAndNotifiesUpdate() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DbSyncNotifier notifier = mock(DbSyncNotifier.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        RedisStore store = new RedisStore(redis, notifier);
        PlayerState state = new PlayerState();
        state.setName("alice");
        state.markDirty(PlayerState.FIELD_NAME);

        assertTrue(store.flushDirty("42", state));
        verify(redis).execute(
                any(RedisScript.class),
                eq(List.of("player:42", "sync:dirty:player")),
                eq("42"),
                eq("name"),
                eq("alice")
        );
        verify(notifier).notify("42", RedisStore.OP_UPDATE);
    }

    @Test
    void flushDirtyWithoutDirtyFieldsDoesNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DbSyncNotifier notifier = mock(DbSyncNotifier.class);
        RedisStore store = new RedisStore(redis, notifier);

        assertTrue(store.flushDirty("42", new PlayerState()));
        verifyNoInteractions(redis, notifier);
    }

    @Test
    void flushDirtyFailureDoesNotNotify() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DbSyncNotifier notifier = mock(DbSyncNotifier.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        RedisStore store = new RedisStore(redis, notifier);
        PlayerState state = new PlayerState();
        state.markDirty(PlayerState.FIELD_NAME);

        assertFalse(store.flushDirty("42", state));
        verifyNoInteractions(notifier);
    }

    @Test
    void createWritesFullHashAndRegistersIndexes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DbSyncNotifier notifier = mock(DbSyncNotifier.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        doReturn(hashOps).when(redis).opsForHash();
        when(redis.opsForSet()).thenReturn(setOps);

        RedisStore store = new RedisStore(redis, notifier);
        PlayerState state = new PlayerState();
        state.setId(42L);
        state.setName("alice");

        assertTrue(store.create("42", state));
        verify(hashOps).putAll("player:42", state.toHash());
        verify(setOps).add(RedisStore.PLAYER_IDX_KEY, "42");
        verify(setOps).add(RedisStore.PLAYER_ONLINE_KEY, "42");
        verifyNoInteractions(notifier);
    }

    @Test
    void destroyExecutesScriptAndNotifiesDestroy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DbSyncNotifier notifier = mock(DbSyncNotifier.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        RedisStore store = new RedisStore(redis, notifier);
        PlayerState state = new PlayerState();
        state.setId(42L);
        state.setName("alice");
        state.setOnline(true);

        assertTrue(store.destroy("42", state));
        verify(redis).execute(
                any(RedisScript.class),
                eq(List.of("player:42", "player:online", "sync:dirty:player")),
                any(Object[].class)
        );
        verify(notifier).notify("42", RedisStore.OP_DESTROY);
    }

    @Test
    void loadReadsHashEntries() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        doReturn(hashOps).when(redis).opsForHash();
        when(hashOps.entries("player:42")).thenReturn(Map.of("name", "alice", "online", "1"));

        RedisStore store = new RedisStore(redis, mock(DbSyncNotifier.class));
        Map<String, String> loaded = store.load("42");
        assertEquals("alice", loaded.get("name"));
        assertEquals("1", loaded.get("online"));
    }

    @Test
    void writeLogAddsJsonPayloadToStream() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streamOps = mock(StreamOperations.class);
        doReturn(streamOps).when(redis).opsForStream();

        RedisStore store = new RedisStore(redis, mock(DbSyncNotifier.class));
        Map<String, String> event = new LinkedHashMap<>();
        event.put("level", "HIGH");
        event.put("content", "\"hello\"");
        store.writeLog(event);

        verify(streamOps).add(eq(RedisStore.LOG_EVENTS_KEY), any(Map.class));
    }
}
