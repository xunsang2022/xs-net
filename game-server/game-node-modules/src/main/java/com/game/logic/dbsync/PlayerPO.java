package com.game.logic.dbsync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** ActiveRecord mapping for the existing users table. */
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true, exclude = {"expectedVersion", "dirtyFields"})
public class PlayerPO extends Model<PlayerPO> {

    public static final String FIELD_ID = "id";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_ONLINE = "online";
    public static final String FIELD_LAST_LOGIN_AT = "last_login_at";
    public static final String FIELD_UPDATED_AT = "updated_at";
    public static final String FIELD_VERSION = "version";

    private static final List<String> KNOWN_FIELDS = List.of(
            FIELD_ID, FIELD_NAME, FIELD_EMAIL, FIELD_ONLINE,
            FIELD_LAST_LOGIN_AT, FIELD_UPDATED_AT, FIELD_VERSION
    );

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String name;
    private String email;
    private Integer online;
    private Timestamp lastLoginAt;
    private Timestamp updatedAt;
    private Integer version;

    @TableField(exist = false)
    private Integer expectedVersion;

    @TableField(exist = false)
    @Getter(AccessLevel.NONE)
    private final Set<String> dirtyFields = new LinkedHashSet<>();

    public static PlayerPO fromDirtyRecord(DirtyRecord record) {
        Map<String, String> fields = record.fields();
        PlayerPO player = new PlayerPO();
        player.id = record.playerId();
        player.name = value(fields, "name", "player-" + record.playerId());
        player.email = value(fields, "email", "");
        player.online = intValue(fields, "online", 0);
        player.lastLoginAt = timestampValue(fields.get("last_login_at"));
        player.updatedAt = timestampValue(fields.get("updated_at"));
        player.version = intValue(fields, "version", Math.toIntExact(record.newVersion()));
        player.expectedVersion = Math.toIntExact(record.expectedVersion());
        return player;
    }

    public boolean isOnline() {
        return Integer.valueOf(1).equals(online);
    }

    public PlayerPO setOnline(boolean online) {
        this.online = online ? 1 : 0;
        return this;
    }

    public long getLastLoginAtMillis() {
        return lastLoginAt == null ? 0L : lastLoginAt.getTime();
    }

    public PlayerPO setLastLoginAtMillis(long value) {
        this.lastLoginAt = new Timestamp(value);
        return this;
    }

    public long getUpdatedAtMillis() {
        return updatedAt == null ? 0L : updatedAt.getTime();
    }

    public PlayerPO setUpdatedAtMillis(long value) {
        this.updatedAt = new Timestamp(value);
        return this;
    }

    public long getVersionValue() {
        return version == null ? 0L : version;
    }

    public PlayerPO setVersionValue(long value) {
        this.version = Math.toIntExact(value);
        return this;
    }

    public void incrementVersion() {
        setVersionValue(getVersionValue() + 1L);
    }

    public void markDirty(String field) {
        if (!KNOWN_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Unknown player field: " + field);
        }
        dirtyFields.add(field);
    }

    public List<String> getDirtyFields() {
        return new ArrayList<>(dirtyFields);
    }

    public boolean hasDirtyFields() {
        return !dirtyFields.isEmpty();
    }

    public void clearDirty() {
        dirtyFields.clear();
    }

    public void clearDirty(Collection<String> fields) {
        dirtyFields.removeAll(Objects.requireNonNull(fields, "fields"));
    }

    public void clearDirtyMatching(Map<String, String> persistedFields) {
        Objects.requireNonNull(persistedFields, "persistedFields").forEach((field, value) -> {
            if (Objects.equals(fieldValue(field), value)) {
                dirtyFields.remove(field);
            }
        });
    }

    public String fieldValue(String field) {
        return switch (field) {
            case FIELD_ID -> id == null ? "" : String.valueOf(id);
            case FIELD_NAME -> name == null ? "" : name;
            case FIELD_EMAIL -> email == null ? "" : email;
            case FIELD_ONLINE -> isOnline() ? "1" : "0";
            case FIELD_LAST_LOGIN_AT -> String.valueOf(getLastLoginAtMillis());
            case FIELD_UPDATED_AT -> String.valueOf(getUpdatedAtMillis());
            case FIELD_VERSION -> String.valueOf(getVersionValue());
            default -> throw new IllegalArgumentException("Unknown player field: " + field);
        };
    }

    public Map<String, String> toHash() {
        return toHash(KNOWN_FIELDS);
    }

    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new java.util.LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    public void fromHash(Map<String, String> fields) {
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (value == null || !KNOWN_FIELDS.contains(field)) {
                continue;
            }
            switch (field) {
                case FIELD_ID -> {
                    if (!value.isBlank()) {
                        id = longValue(value, 0L);
                    }
                }
                case FIELD_NAME -> name = value;
                case FIELD_EMAIL -> email = value;
                case FIELD_ONLINE -> online = "1".equals(value) || "true".equalsIgnoreCase(value) ? 1 : 0;
                case FIELD_LAST_LOGIN_AT -> lastLoginAt = new Timestamp(longValue(value, 0L));
                case FIELD_UPDATED_AT -> updatedAt = new Timestamp(longValue(value, 0L));
                case FIELD_VERSION -> version = intValue(fields, FIELD_VERSION, 0);
                default -> {
                    // ignore unknown fields
                }
            }
        }
    }

    private static String value(Map<String, String> fields, String key, String defaultValue) {
        String value = fields.get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intValue(Map<String, String> fields, String key, int defaultValue) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static Timestamp timestampValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new Timestamp(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long longValue(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

}
