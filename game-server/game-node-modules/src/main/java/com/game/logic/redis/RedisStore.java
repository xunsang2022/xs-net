package com.game.logic.redis;

import com.game.logic.enums.LogLevel;
import com.game.logic.enums.LogType;
import com.game.logic.player.PlayerState;
import com.game.protocol.pb.LogRecordMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Base64;
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
        LogRecordMessage.Builder builder = LogRecordMessage.newBuilder();
        Integer level = parseEnumOrdinal(event.get("level"), LogLevel.values());
        if (level != null) {
            builder.setLevel(level);
        }
        Integer type = parseEnumOrdinal(event.get("type"), LogType.values());
        if (type != null) {
            builder.setType(type);
        }
        String content = event.get("content");
        if (content != null) {
            builder.setMessage(content);
        }
        String playerId = event.get("playerId");
        if (playerId != null && !playerId.isBlank()) {
            try {
                builder.setPlayerId(Long.parseLong(playerId));
            } catch (NumberFormatException ignored) {
                // 非法 playerId 忽略
            }
        }

        Map<String, String> record = new LinkedHashMap<>();
        record.put("payload", Base64.getEncoder().encodeToString(builder.build().toByteArray()));
        record.put("createTime", String.valueOf(System.currentTimeMillis()));
        redis.opsForStream().add(LOG_EVENTS_KEY, record);
    }

    private static Integer parseEnumOrdinal(String text, Enum<?>[] values) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Enum<?> value : values) {
            if (value.name().equals(text)) {
                return value.ordinal();
            }
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
