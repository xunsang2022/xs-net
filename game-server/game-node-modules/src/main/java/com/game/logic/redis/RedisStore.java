package com.game.logic.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.player.PlayerState;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis access layer for logic node actor state and log events.
 */
public class RedisStore {

    public static final String PLAYER_KEY_PREFIX = "player:";
    public static final String PLAYER_IDX_KEY = "idx:player";
    public static final String PLAYER_ONLINE_KEY = "player:online";
    public static final String DIRTY_KEY = "sync:dirty:player";
    public static final String LOG_EVENTS_KEY = "log:events";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DESTROY = "DESTROY";

    public static final String FLUSH_DIRTY_LUA = """
            local playerKey = KEYS[1]
            local dirtyKey = KEYS[2]
            local playerId = ARGV[1]
            for i = 2, #ARGV, 2 do
              redis.call('HSET', playerKey, ARGV[i], ARGV[i + 1])
            end
            redis.call('SADD', dirtyKey, playerId)
            return 1
            """;

    public static final String DESTROY_LUA = """
            local playerKey = KEYS[1]
            local onlineKey = KEYS[2]
            local dirtyKey = KEYS[3]
            local playerId = ARGV[1]
            for i = 2, #ARGV, 2 do
              redis.call('HSET', playerKey, ARGV[i], ARGV[i + 1])
            end
            redis.call('SREM', onlineKey, playerId)
            redis.call('SADD', dirtyKey, playerId)
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DbSyncNotifier notifier;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RedisScript<Long> flushDirtyScript;
    private final RedisScript<Long> destroyScript;

    public RedisStore(StringRedisTemplate redis, DbSyncNotifier notifier) {
        this.redis = redis;
        this.notifier = notifier;
        this.flushDirtyScript = buildScript(FLUSH_DIRTY_LUA);
        this.destroyScript = buildScript(DESTROY_LUA);
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
        List<String> dirtyFields = state.getDirtyFields();
        if (dirtyFields.isEmpty()) {
            return true;
        }

        List<Object> args = new ArrayList<>();
        args.add(playerId);
        for (String field : dirtyFields) {
            args.add(field);
            args.add(state.fieldValue(field));
        }

        Long result = redis.execute(
                flushDirtyScript,
                List.of(playerKey(playerId), DIRTY_KEY),
                args.toArray()
        );
        boolean ok = Long.valueOf(1L).equals(result);
        if (ok) {
            notifier.notify(playerId, OP_UPDATE);
        }
        return ok;
    }

    public boolean destroy(String playerId, PlayerState state) {
        List<Object> args = new ArrayList<>();
        args.add(playerId);
        for (Map.Entry<String, String> entry : state.toHash().entrySet()) {
            args.add(entry.getKey());
            args.add(entry.getValue());
        }

        Long result = redis.execute(
                destroyScript,
                List.of(playerKey(playerId), PLAYER_ONLINE_KEY, DIRTY_KEY),
                args.toArray()
        );
        boolean ok = Long.valueOf(1L).equals(result);
        if (ok) {
            notifier.notify(playerId, OP_DESTROY);
        }
        return ok;
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

    private RedisScript<Long> buildScript(String script) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}
