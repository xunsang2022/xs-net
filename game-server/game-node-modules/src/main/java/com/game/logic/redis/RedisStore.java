package com.game.logic.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.player.PlayerState;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis access layer for logic node actor state and log events.
 */
public class RedisStore {

    public static final String PLAYER_KEY_PREFIX = "player:";
    public static final String PLAYER_IDX_KEY = "idx:player";
    public static final String PLAYER_ONLINE_KEY = "player:online";
    public static final String LOG_EVENTS_KEY = "log:events";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String playerKey(String playerId) {
        return PLAYER_KEY_PREFIX + playerId;
    }

    public boolean exists(String playerId) {
        return Boolean.TRUE.equals(redis.hasKey(playerKey(playerId)));
    }

    public Map<String, String> load(String playerId) {
        Map<Object, Object> entries = redis.opsForHash().entries(playerKey(playerId));
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return result;
    }

    public boolean create(String playerId, PlayerState state) {
        redis.opsForHash().putAll(playerKey(playerId), state.toHash());
        redis.opsForSet().add(PLAYER_IDX_KEY, playerId);
        redis.opsForSet().add(PLAYER_ONLINE_KEY, playerId);
        return true;
    }

    public boolean flushDirty(String playerId, PlayerState state) {
        Map<String, String> dirty = state.toHash(state.getDirtyFields());
        if (dirty.isEmpty()) {
            return true;
        }
        redis.opsForHash().putAll(playerKey(playerId), dirty);
        return true;
    }

    public boolean destroy(String playerId, PlayerState state) {
        redis.opsForHash().putAll(playerKey(playerId), state.toHash());
        redis.opsForSet().remove(PLAYER_ONLINE_KEY, playerId);
        return true;
    }

    public void writeLog(Map<String, String> event) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("payload", toJson(event));
        record.put("createTime", String.valueOf(System.currentTimeMillis()));
        redis.opsForStream().add(LOG_EVENTS_KEY, record);
    }

    private String toJson(Map<String, String> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
