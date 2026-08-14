package com.game.dbsync;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PlayerUpsertSql {
    public static final String SQL = "INSERT INTO users (id, name, email, online, last_login_at, updated_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET "
            + "name = COALESCE(EXCLUDED.name, users.name), "
            + "email = COALESCE(EXCLUDED.email, users.email), "
            + "online = COALESCE(EXCLUDED.online, users.online), "
            + "last_login_at = COALESCE(EXCLUDED.last_login_at, users.last_login_at), "
            + "updated_at = COALESCE(EXCLUDED.updated_at, users.updated_at), "
            + "version = COALESCE(EXCLUDED.version, users.version)";

    private PlayerUpsertSql() {
    }

    public static Object[] params(Long playerId, Map<String, String> fields) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        String id = value(fields, "id", String.valueOf(playerId));
        String name = value(fields, "name", "player-" + playerId);
        String email = value(fields, "email", "");
        Integer online = intValue(fields, "online", 0);
        Timestamp lastLoginAt = timestampValue(fields.get("last_login_at"));
        Timestamp updatedAt = timestampValue(fields.get("updated_at"));
        Long version = longValue(fields.get("version"));
        return new Object[]{
                Long.parseLong(id),
                name,
                email,
                online,
                lastLoginAt,
                updatedAt,
                version == null ? 1L : version
        };
    }

    public static List<Object[]> batchParams(List<Map<String, String>> records) {
        List<Object[]> batch = new ArrayList<>(records.size());
        for (Map<String, String> record : records) {
            Long playerId = Long.valueOf(value(record, "id", "0"));
            batch.add(params(playerId, record));
        }
        return batch;
    }

    private static String value(Map<String, String> fields, String key, String defaultValue) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Integer intValue(Map<String, String> fields, String key, int defaultValue) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long longValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Timestamp timestampValue(String value) {
        Long millis = longValue(value);
        return millis == null ? null : new Timestamp(millis);
    }
}
