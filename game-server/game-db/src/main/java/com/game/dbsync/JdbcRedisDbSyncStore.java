package com.game.dbsync;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JdbcRedisDbSyncStore implements DbSyncStore {
    public static final String PLAYER_HASH_PREFIX = "player:";
    public static final String DIRTY_SET_KEY = "sync:dirty:player";
    public static final String PLAYER_INDEX_KEY = "idx:player";
    public static final String LOG_EVENTS_KEY = "log:events";
    public static final String LOG_GROUP = "db-sync";
    public static final String LOG_CONSUMER = "log-consumer";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;

    public JdbcRedisDbSyncStore(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    @Override
    public Map<String, String> loadPlayerHash(Long playerId) {
        Map<Object, Object> entries = redis.opsForHash().entries(playerKey(playerId));
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return fields;
    }

    @Override
    public Map<String, String> loadOrCreatePlayer(Long playerId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, online, last_login_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 0, NOW(), NOW(), 1) ON CONFLICT (id) DO NOTHING",
                playerId,
                "player-" + playerId,
                "");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, name, email, online, last_login_at, updated_at, version FROM users WHERE id = ?",
                playerId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", String.valueOf(row.get("id")));
        fields.put("name", row.get("name") == null ? "player-" + playerId : String.valueOf(row.get("name")));
        fields.put("email", row.get("email") == null ? "" : String.valueOf(row.get("email")));
        fields.put("online", String.valueOf(row.get("online") == null ? 0 : row.get("online")));
        fields.put("last_login_at", timestampString(row.get("last_login_at")));
        fields.put("updated_at", timestampString(row.get("updated_at")));
        fields.put("version", String.valueOf(row.get("version") == null ? 1 : row.get("version")));
        return fields;
    }

    @Override
    public int[] batchUpsertPlayers(List<Map<String, String>> records) {
        return jdbcTemplate.batchUpdate(PlayerUpsertSql.SQL, PlayerUpsertSql.batchParams(records));
    }

    @Override
    public void markSynced(Long playerId) {
        redis.opsForSet().remove(DIRTY_SET_KEY, String.valueOf(playerId));
    }

    @Override
    public void writePlayerHashAndIndex(Long playerId, Map<String, String> fields) {
        redis.opsForHash().putAll(playerKey(playerId), fields);
        redis.opsForSet().add(PLAYER_INDEX_KEY, String.valueOf(playerId));
    }

    @Override
    public long logBacklog() {
        Long size = redis.opsForStream().size(LOG_EVENTS_KEY);
        return size == null ? 0L : size;
    }

    @Override
    public List<LogStreamRecord> readLogEvents(int batchSize) {
        ensureLogGroup();
        List<MapRecord<String, String, String>> records = redis.<String, String>opsForStream().read(
                Consumer.from(LOG_GROUP, LOG_CONSUMER),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(LOG_EVENTS_KEY, ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(record -> new LogStreamRecord(record.getId().getValue(), record.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public void ackLogEvents(List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }
        redis.opsForStream().acknowledge(LOG_EVENTS_KEY, LOG_GROUP, recordIds.toArray(new String[0]));
    }

    private void ensureLogGroup() {
        try {
            redis.opsForStream().createGroup(LOG_EVENTS_KEY, ReadOffset.from("0"), LOG_GROUP);
        } catch (RedisSystemException e) {
            if (e.getMostSpecificCause() == null
                    || e.getMostSpecificCause().getMessage() == null
                    || !e.getMostSpecificCause().getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() == null
                    || e.getMostSpecificCause().getMessage() == null
                    || !e.getMostSpecificCause().getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static String playerKey(Long playerId) {
        return PLAYER_HASH_PREFIX + playerId;
    }

    private static String timestampString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp timestamp) {
            return String.valueOf(timestamp.getTime());
        }
        if (value instanceof java.util.Date date) {
            return String.valueOf(date.getTime());
        }
        return String.valueOf(value);
    }
}
