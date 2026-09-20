package com.game.logic.redis;

import com.game.logic.enums.LogLevel;
import com.game.logic.enums.LogType;
import com.game.logic.dbsync.PlayerPO;
import com.game.protocol.pb.LogRecordCommand;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Logic 节点 Actor 状态和日志事件的 Redis 访问层。 */
@RequiredArgsConstructor
public class RedisStore {

    public static final String PLAYER_KEY_PREFIX = "player:";
    public static final String PLAYER_IDX_KEY = "idx:player";
    public static final String PLAYER_ONLINE_KEY = "player:online";
    public static final String LOG_EVENTS_KEY = "log:events";

    /** 由 Logic 启动流程装配的 Redis 模板。 */
    @NonNull
    private final StringRedisTemplate redis;

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

    public boolean create(String playerId, PlayerPO state) {
        return create(playerId, state.toHash());
    }

    public boolean create(String playerId, Map<String, String> fields) {
        redis.opsForHash().putAll(playerKey(playerId), fields);
        redis.opsForSet().add(PLAYER_IDX_KEY, playerId);
        redis.opsForSet().add(PLAYER_ONLINE_KEY, playerId);
        return true;
    }

    public boolean flushDirty(String playerId, PlayerPO state) {
        return flushDirty(playerId, state.toHash(state.getDirtyFields()));
    }

    public boolean flushDirty(String playerId, Map<String, String> dirty) {
        if (dirty.isEmpty()) {
            return true;
        }
        redis.opsForHash().putAll(playerKey(playerId), dirty);
        return true;
    }

    public boolean destroy(String playerId, PlayerPO state) {
        return destroy(playerId, state.toHash());
    }

    public boolean destroy(String playerId, Map<String, String> fields) {
        redis.opsForHash().putAll(playerKey(playerId), fields);
        redis.opsForSet().remove(PLAYER_ONLINE_KEY, playerId);
        return true;
    }

    public void writeLog(Map<String, String> event) {
        LogRecordCommand.Builder builder = LogRecordCommand.newBuilder();
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
