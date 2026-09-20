package com.game.logic.redis;

import com.game.logic.enums.LogLevel;
import com.game.logic.enums.LogType;
import com.game.logic.dbsync.PlayerPO;
import com.game.protocol.pb.LogRecordCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void flushDirtyWritesDirtyFieldsToHash() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        doReturn(hashOps).when(redis).opsForHash();

        RedisStore store = new RedisStore(redis);
        PlayerPO state = new PlayerPO();
        state.setName("alice");
        state.markDirty(PlayerPO.FIELD_NAME);

        assertTrue(store.flushDirty("42", state));
        verify(hashOps).putAll("player:42", Map.of("name", "alice"));
    }

    @Test
    void flushDirtyWithoutDirtyFieldsDoesNothing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisStore store = new RedisStore(redis);

        assertTrue(store.flushDirty("42", new PlayerPO()));
        verifyNoInteractions(redis);
    }

    @Test
    void createWritesFullHashAndRegistersIndexes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        doReturn(hashOps).when(redis).opsForHash();
        doReturn(setOps).when(redis).opsForSet();

        RedisStore store = new RedisStore(redis);
        PlayerPO state = new PlayerPO();
        state.setId(42L);
        state.setName("alice");

        assertTrue(store.create("42", state));
        verify(hashOps).putAll("player:42", state.toHash());
        verify(setOps).add(RedisStore.PLAYER_IDX_KEY, "42");
        verify(setOps).add(RedisStore.PLAYER_ONLINE_KEY, "42");
    }

    @Test
    void destroyWritesHashAndRemovesOnline() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        doReturn(hashOps).when(redis).opsForHash();
        doReturn(setOps).when(redis).opsForSet();

        RedisStore store = new RedisStore(redis);
        PlayerPO state = new PlayerPO();
        state.setId(42L);
        state.setName("alice");
        state.setOnline(true);

        assertTrue(store.destroy("42", state));
        verify(hashOps).putAll("player:42", state.toHash());
        verify(setOps).remove(RedisStore.PLAYER_ONLINE_KEY, "42");
    }

    @Test
    void loadReadsHashEntries() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps = mock(HashOperations.class);
        doReturn(hashOps).when(redis).opsForHash();
        when(hashOps.entries("player:42")).thenReturn(Map.of("name", "alice", "online", "1"));

        RedisStore store = new RedisStore(redis);
        Map<String, String> loaded = store.load("42");
        assertEquals("alice", loaded.get("name"));
        assertEquals("1", loaded.get("online"));
    }

    @Test
    void writeLogAddsProtoPayloadToStream() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> streamOps = mock(StreamOperations.class);
        doReturn(streamOps).when(redis).opsForStream();

        RedisStore store = new RedisStore(redis);
        Map<String, String> event = new LinkedHashMap<>();
        event.put("level", "HIGH");
        event.put("type", "LOGIN");
        event.put("content", "hello");
        event.put("playerId", "42");
        store.writeLog(event);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq(RedisStore.LOG_EVENTS_KEY), captor.capture());
        Map<String, String> record = captor.getValue();
        assertNotNull(record.get("createTime"));
        LogRecordCommand msg = LogRecordCommand.parseFrom(Base64.getDecoder().decode(record.get("payload")));
        assertEquals(LogLevel.HIGH.ordinal(), msg.getLevel());
        assertEquals(LogType.LOGIN.ordinal(), msg.getType());
        assertEquals("hello", msg.getMessage());
        assertEquals(42L, msg.getPlayerId());
    }
}
