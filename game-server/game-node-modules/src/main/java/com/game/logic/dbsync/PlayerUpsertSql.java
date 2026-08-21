package com.game.logic.dbsync;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 玩家 UPSERT SQL 常量与批量参数构造。
 * 使用 PostgreSQL 的 ON CONFLICT (id) DO UPDATE，并通过 WHERE users.version = ? 实现乐观锁：
 * 仅当数据库中的 version 与提交时的 version 一致时才更新，避免覆盖并发修改。
 */
public final class PlayerUpsertSql {

    public static final String SQL = "INSERT INTO users (id, name, email, online, last_login_at, updated_at, version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO UPDATE SET "
            + "name = EXCLUDED.name, "
            + "email = EXCLUDED.email, "
            + "online = EXCLUDED.online, "
            + "last_login_at = EXCLUDED.last_login_at, "
            + "updated_at = EXCLUDED.updated_at, "
            + "version = EXCLUDED.version "
            + "WHERE users.version = ?";

    private PlayerUpsertSql() {
    }

    /**
     * 构造单条 UPSERT 参数。
     * 参数顺序：7 个插入值（id, name, email, online, last_login_at, updated_at, version）+ 1 个乐观锁 version 条件。
     */
    public static Object[] params(DirtyRecord r) {
        if (r == null || r.playerId() == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        Map<String, String> fields = r.fields();
        String id = value(fields, "id", String.valueOf(r.playerId()));
        String name = value(fields, "name", "player-" + r.playerId());
        String email = value(fields, "email", "");
        Integer online = intValue(fields, "online", 0);
        Timestamp lastLoginAt = timestampValue(fields.get("last_login_at"));
        Timestamp updatedAt = timestampValue(fields.get("updated_at"));
        Long version = longValue(fields.get("version"));
        long resolvedVersion = version == null ? r.version() : version;
        return new Object[]{
                Long.parseLong(id),
                name,
                email,
                online,
                lastLoginAt,
                updatedAt,
                resolvedVersion,
                resolvedVersion
        };
    }

    /**
     * 构造批量 UPSERT 参数。
     */
    public static List<Object[]> batchParams(List<DirtyRecord> records) {
        List<Object[]> batch = new ArrayList<>(records.size());
        for (DirtyRecord record : records) {
            batch.add(params(record));
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
