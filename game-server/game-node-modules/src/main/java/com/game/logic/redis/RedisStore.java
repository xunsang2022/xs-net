package com.game.logic.redis;

import com.game.eventsourcing.LogLevel;
import com.game.eventsourcing.LogType;
import com.game.logic.actor.player.PlayerPO;
import com.game.protocol.pb.LogRecordCommand;
import lombok.NonNull;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Logic 节点 Actor 状态和日志事件的 Redis 访问层。 */
public class RedisStore {

    public static final String PLAYER_KEY_PREFIX = "playerActor:";
    public static final String PLAYER_IDX_KEY = "idx:player";
    public static final String PLAYER_ONLINE_KEY = "player:online";
    public static final String LOG_EVENTS_KEY = "log:events";

    /** 由 Logic 启动流程装配的 Redis 模板。 */
    @NonNull
    private final StringRedisTemplate redis;
    /** 玩家 PO 专用 JSON Hash 模板；日志和索引仍使用字符串模板。 */
    private final RedisTemplate<String, Object> playerRedis;
    private final XsModelHashMapper<PlayerPO> playerHashMapper = new XsModelHashMapper<>(PlayerPO.class);

    public RedisStore(StringRedisTemplate redis) {
        this(redis, null);
    }

    public RedisStore(StringRedisTemplate redis, RedisTemplate<String, Object> playerRedis) {
        this.redis = redis;
        this.playerRedis = playerRedis;
    }

    public static String playerKey(String playerId) {
        return PLAYER_KEY_PREFIX + playerId;
    }

    public boolean exists(String playerId) {
        return Boolean.TRUE.equals(redis.hasKey(playerKey(playerId)));
    }

    public Map<String, String> load(String playerId) {
        Map<?, ?> entries = playerHash().entries(playerKey(playerId));
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            result.put(String.valueOf(entry.getKey()), textValue(entry.getValue()));
        }
        return result;
    }

    public boolean create(String playerId, PlayerPO state) {
        if (playerRedis == null) {
            return create(playerId, state.toHash());
        }
        putHash(playerKey(playerId), playerHashMapper.toHash(state));
        registerOnline(playerId);
        return true;
    }

    public boolean create(String playerId, Map<String, String> fields) {
        putHash(playerKey(playerId), playerRedis == null
                ? fields
                : playerHashMapper.toHash(playerHashMapper.fromStringHash(fields)));
        registerOnline(playerId);
        return true;
    }

    public boolean flushDirty(String playerId, PlayerPO state) {
        if (playerRedis == null) {
            return flushDirty(playerId, state.toHash(state.getDirtyFields()));
        }
        Map<String, Object> dirty = playerHashMapper.toHash(state, state.getDirtyFields());
        if (dirty.isEmpty()) {
            return true;
        }
        putHash(playerKey(playerId), dirty);
        return true;
    }

    public boolean flushDirty(String playerId, Map<String, String> dirty) {
        if (dirty.isEmpty()) {
            return true;
        }
        putHash(playerKey(playerId), playerRedis == null
                ? dirty
                : playerHashMapper.toHash(playerHashMapper.fromStringHash(dirty)));
        return true;
    }

    public boolean destroy(String playerId, PlayerPO state) {
        return deletePlayerCache(playerId);
    }

    public boolean destroy(String playerId, Map<String, String> fields) {
        return deletePlayerCache(playerId);
    }

    /** 删除玩家状态缓存及其索引；数据库快照由 DBSyncEngine 异步持久化。 */
    public boolean deletePlayerCache(String playerId) {
        redis.delete(playerKey(playerId));
        redis.opsForSet().remove(PLAYER_IDX_KEY, playerId);
        redis.opsForSet().remove(PLAYER_ONLINE_KEY, playerId);
        return true;
    }

    /** Writes the complete PO to its JSON Redis hash. */
    public boolean saveRedis(PlayerPO state) {
        if (playerRedis == null) {
            redis.opsForHash().putAll(playerKey(String.valueOf(state.getId())), state.toHash());
        } else {
            putHash(playerKey(String.valueOf(state.getId())), playerHashMapper.toHash(state));
        }
        state.clearDirtyMatching(state.toHash());
        return true;
    }

    private void registerOnline(String playerId) {
        redis.opsForSet().add(PLAYER_IDX_KEY, playerId);
        redis.opsForSet().add(PLAYER_ONLINE_KEY, playerId);
    }

    @SuppressWarnings("unchecked")
    private HashOperations<String, String, Object> playerHash() {
        if (playerRedis != null) {
            return playerRedis.opsForHash();
        }
        return (HashOperations<String, String, Object>) (HashOperations<?, ?, ?>) redis.opsForHash();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void putHash(String key, Map<String, ?> values) {
        ((HashOperations) playerHash()).putAll(key, values);
    }

    private static String textValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
