package com.game.dbsync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "game.db.backfill", name = "enabled", havingValue = "true")
public class BackfillRunner implements CommandLineRunner {
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;

    public BackfillRunner(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
    }

    @Override
    public void run(String... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, email, online, last_login_at, updated_at, version FROM users ORDER BY id");
        int total = 0;
        int missing = 0;
        List<String> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long playerId = ((Number) row.get("id")).longValue();
            String key = JdbcRedisDbSyncStore.PLAYER_HASH_PREFIX + playerId;
            Map<String, String> pgFields = toFields(row, playerId);
            Map<Object, Object> existing = redis.opsForHash().entries(key);
            if (existing.isEmpty()) {
                missing++;
            } else if (samples.size() < 20) {
                List<String> diffs = diff(existing, pgFields);
                if (!diffs.isEmpty()) {
                    samples.add(key + " -> " + String.join(",", diffs));
                }
            }
            redis.opsForHash().putAll(key, pgFields);
            redis.opsForSet().add(JdbcRedisDbSyncStore.PLAYER_INDEX_KEY, String.valueOf(playerId));
            total++;
        }
        log.info("Backfill finished: total={}, missingBeforeWrite={}, fieldDiffSamples={}",
                total, missing, samples);
    }

    private Map<String, String> toFields(Map<String, Object> row, Long playerId) {
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

    private List<String> diff(Map<Object, Object> existing, Map<String, String> pgFields) {
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> entry : pgFields.entrySet()) {
            String current = existing.get(entry.getKey()) == null
                    ? null
                    : String.valueOf(existing.get(entry.getKey()));
            if (current == null || !current.equals(entry.getValue())) {
                diffs.add(entry.getKey());
            }
        }
        return diffs;
    }

    private String timestampString(Object value) {
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
