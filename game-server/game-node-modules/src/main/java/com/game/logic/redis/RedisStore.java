package com.game.logic.redis;

import com.game.eventsourcing.log.LogLevel;
import com.game.eventsourcing.log.LogType;
import com.game.logic.actor.player.db.PlayerPO;
import com.game.protocol.pb.LogRecordCommand;
import com.xs.kernel.model.RedisKey;
import com.xs.kernel.model.XsModel;
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

    /**
     * 根据 @RedisKey 注解从 Redis 加载 XsModel 实例。
     *
     * @param entityId 实体 ID
     * @param type     模型类型
     * @return 模型实例，如果 Redis 中不存在则返回 null
     */
    public <T extends XsModel<T>> T loadModel(String entityId, Class<T> type) {
        RedisKey annotation = type.getAnnotation(RedisKey.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Model must be annotated with @RedisKey: " + type.getName());
        }
        String key = annotation.value() + entityId;
        Map<?, ?> entries = playerHash().entries(key);
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, String> hash = new HashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            hash.put(String.valueOf(entry.getKey()), textValue(entry.getValue()));
        }
        XsModelHashMapper<T> mapper = new XsModelHashMapper<>(type);
        return mapper.fromStringHash(hash);
    }

    /**
     * 将 XsModel 实例保存到 Redis。
     *
     * @param model    模型实例
     * @param entityId 实体 ID
     */
    public <T extends XsModel<T>> void saveModel(XsModel<T> model, String entityId) {
        RedisKey annotation = model.getClass().getAnnotation(RedisKey.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Model must be annotated with @RedisKey: " + model.getClass().getName());
        }
        String key = annotation.value() + entityId;
        XsModelHashMapper<T> mapper = new XsModelHashMapper<>((Class<T>) model.getClass());
        putHash(key, mapper.toHash((T) model));
    }
}
