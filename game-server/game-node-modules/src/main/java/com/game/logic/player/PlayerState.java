package com.game.logic.player;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Player runtime state stored in Redis hash {@code player:{playerId}}.
 */
@Getter
@Setter
public class PlayerState {
    public static final String FIELD_ID = "id";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_ONLINE = "online";
    public static final String FIELD_LAST_LOGIN_AT = "last_login_at";
    public static final String FIELD_UPDATED_AT = "updated_at";
    public static final String FIELD_VERSION = "version";

    private static final Set<String> KNOWN_FIELDS = Set.of(
            FIELD_ID, FIELD_NAME, FIELD_EMAIL, FIELD_ONLINE,
            FIELD_LAST_LOGIN_AT, FIELD_UPDATED_AT, FIELD_VERSION
    );

    private final Set<String> dirtyFields = new LinkedHashSet<>();

    private Long id;
    private String name;
    private String email;
    private boolean online;
    private long lastLoginAt;
    private long updatedAt;
    private long version;

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

    /**
     * Clears only fields that still contain the value persisted by an asynchronous write.
     * A newer actor message may have changed a field while the write was in flight.
     */
    public void clearDirtyMatching(Map<String, String> persistedFields) {
        Objects.requireNonNull(persistedFields, "persistedFields").forEach((field, value) -> {
            if (Objects.equals(fieldValue(field), value)) {
                dirtyFields.remove(field);
            }
        });
    }

    public void incrementVersion() {
        version++;
    }

    public String fieldValue(String field) {
        return switch (field) {
            case FIELD_ID -> id == null ? "" : String.valueOf(id);
            case FIELD_NAME -> name == null ? "" : name;
            case FIELD_EMAIL -> email == null ? "" : email;
            case FIELD_ONLINE -> online ? "1" : "0";
            case FIELD_LAST_LOGIN_AT -> String.valueOf(lastLoginAt);
            case FIELD_UPDATED_AT -> String.valueOf(updatedAt);
            case FIELD_VERSION -> String.valueOf(version);
            default -> throw new IllegalArgumentException("Unknown player field: " + field);
        };
    }

    public Map<String, String> toHash() {
        Map<String, String> hash = new LinkedHashMap<>();
        for (String field : KNOWN_FIELDS) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new LinkedHashMap<>();
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
                        id = parseLong(value);
                    }
                }
                case FIELD_NAME -> name = value;
                case FIELD_EMAIL -> email = value;
                case FIELD_ONLINE -> online = "1".equals(value) || "true".equalsIgnoreCase(value);
                case FIELD_LAST_LOGIN_AT -> lastLoginAt = parseLong(value);
                case FIELD_UPDATED_AT -> updatedAt = parseLong(value);
                case FIELD_VERSION -> version = parseLong(value);
                default -> {
                    // ignore
                }
            }
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
