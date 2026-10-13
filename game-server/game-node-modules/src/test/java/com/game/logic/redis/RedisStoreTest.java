package com.game.logic.redis;

import com.game.eventsourcing.log.LogLevel;
import com.game.eventsourcing.log.LogType;
import com.game.logic.actor.player.db.PlayerPO;
import com.game.protocol.pb.LogRecordCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
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

class RedisStoreTest {

    @Test
    void playerKeyUsesPlayerPrefix() {
        assertEquals("playerActor:42", RedisStore.playerKey("42"));
    }

    @Test
    void jsonPlayerTemplateStoresTypedHashValuesUnderActorKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisTemplate<String, Object> playerRedis = mock(RedisTemplate.class);
        HashOperations<String, String, Object> hashOps = mock(HashOperations.class);
        doReturn(hashOps).when(playerRedis).opsForHash();

        RedisStore store = new RedisStore(redis, playerRedis);
        PlayerPO state = new PlayerPO().setId(42L).setName("alice").setOnline(1);

        assertTrue(store.saveRedis(state));
        verify(hashOps).putAll("playerActor:42", new XsModelHashMapper<>(PlayerPO.class).toHash(state));
    }

    @Test
    void deletePlayerCacheDeletesPlayerCacheAndIndexes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        doReturn(setOps).when(redis).opsForSet();

        RedisStore store = new RedisStore(redis);

        assertTrue(store.deletePlayerCache("42"));
        verify(redis).delete("playerActor:42");
        verify(setOps).remove(RedisStore.PLAYER_IDX_KEY, "42");
        verify(setOps).remove(RedisStore.PLAYER_ONLINE_KEY, "42");
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
