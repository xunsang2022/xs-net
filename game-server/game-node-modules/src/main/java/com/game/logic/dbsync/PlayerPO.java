package com.game.logic.dbsync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import com.game.logic.context.LogicContext;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.invoke.SerializedLambda;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** ActiveRecord mapping for the existing users table. */
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true, exclude = {"expectedVersion", "dirtyFields"})
public class PlayerPO extends Model<PlayerPO> {

    private static final List<Property> PROPERTIES = properties(PlayerPO.class);
    private static final Map<String, Property> PROPERTIES_BY_COLUMN = PROPERTIES.stream()
            .collect(Collectors.toUnmodifiableMap(Property::column, property -> property));

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
        player.fromHash(fields);
        player.name = value(fields, "name", "player-" + record.playerId());
        player.email = value(fields, "email", "");
        player.online = intValue(fields, "online", 0);
        player.lastLoginAt = timestampValue(fields.get("last_login_at"));
        player.updatedAt = timestampValue(fields.get("updated_at"));
        player.version = intValue(fields, "version", Math.toIntExact(record.newVersion()));
        player.expectedVersion = Math.toIntExact(record.expectedVersion());
        return player;
    }

    /** Setter method reference accepted by the fluent dirty-tracking API. */
    @FunctionalInterface
    public interface ModelPropertySetter<M extends Model<M>, V> extends Serializable {
        void apply(M model, V value);
    }

    /**
     * Invokes a method reference, verifies its declaring class is a MyBatis-Plus
     * model, marks the matching database field dirty, and returns this PO.
     */
    public <V> PlayerPO setProperty(ModelPropertySetter<PlayerPO, V> setter, V value) {
        Objects.requireNonNull(setter, "setter");
        SerializedLambda lambda = serializedLambda(setter);
        Class<?> declaringClass = declaringClass(lambda);
        if (!Model.class.isAssignableFrom(declaringClass)) {
            throw new IllegalArgumentException("setProperty method owner must extend Model: "
                    + declaringClass.getName());
        }
        if (!declaringClass.isAssignableFrom(getClass())) {
            throw new IllegalArgumentException("setProperty method is not applicable to "
                    + getClass().getName());
        }
        if (lambda.getImplMethodKind() == MethodHandleInfo.REF_invokeStatic) {
            throw new IllegalArgumentException("setProperty requires an instance setter method");
        }
        Property property = propertyForSetter(getClass(), lambda.getImplMethodName());
        if (property == null) {
            throw new IllegalArgumentException("Unknown model setter: " + lambda.getImplMethodName());
        }
        captureExpectedVersion();
        setter.apply(this, value);
        markDirty(property.column());
        return this;
    }

    /** Persists the complete PO into the player Redis hash. */
    public boolean saveRedis() {
        return LogicContext.getRedisStore().saveRedis(this);
    }

    /** Performs the custom optimistic-lock UPSERT and then writes the Redis hash. */
    public boolean saveDb() {
        if (!LogicContext.getDbSyncEngine().saveNow(this)) {
            return false;
        }
        return saveRedis();
    }

    void copyPersistedState(PlayerPO persisted) {
        this.id = persisted.id;
        this.name = persisted.name;
        this.email = persisted.email;
        this.online = persisted.online;
        this.lastLoginAt = persisted.lastLoginAt;
        this.updatedAt = persisted.updatedAt;
        this.version = persisted.version;
        this.expectedVersion = persisted.version;
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
        if (!PROPERTIES_BY_COLUMN.containsKey(field)) {
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
        Property property = property(field);
        return textValue(read(property.field()));
    }

    public Map<String, String> toHash() {
        return toHash(PROPERTIES.stream().map(Property::column).toList());
    }

    public Map<String, String> toHash(Collection<String> fields) {
        Map<String, String> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, fieldValue(field));
        }
        return hash;
    }

    /** Returns typed values for a JSON-serializing Redis hash template. */
    public Map<String, Object> toRedisHash() {
        return toRedisHash(PROPERTIES.stream().map(Property::column).toList());
    }

    /** Returns selected typed values for a JSON-serializing Redis hash template. */
    public Map<String, Object> toRedisHash(Collection<String> fields) {
        Map<String, Object> hash = new LinkedHashMap<>();
        for (String field : fields) {
            hash.put(field, redisValue(property(field)));
        }
        return hash;
    }

    public void fromHash(Map<?, ?> fields) {
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null || !PROPERTIES_BY_COLUMN.containsKey(field)) {
                continue;
            }
            Property property = property(field);
            Object converted = convertValue(value, property.field().getType());
            try {
                property.field().setAccessible(true);
                property.field().set(this, converted);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Unable to restore player field: " + field, exception);
            }
        }
    }

    public static Map<String, Object> redisValues(Map<String, String> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        fields.forEach((field, value) -> values.put(field, redisValue(field, value)));
        return values;
    }

    private static Object redisValue(String field, String value) {
        Property property = PROPERTIES_BY_COLUMN.get(field);
        if (property == null) {
            throw new IllegalArgumentException("Unknown player field: " + field);
        }
        return switch (property.field().getType().getSimpleName()) {
            case "Integer", "int" -> intValue(Map.of(field, value), field, 0);
            case "Long", "long" -> longValue(value, 0L);
            case "Timestamp" -> longValue(value, 0L);
            default -> value == null ? "" : value;
        };
    }

    private Object redisValue(Property property) {
        Object value = read(property.field());
        if (value == null) {
            return switch (property.field().getType().getSimpleName()) {
                case "Integer", "int", "Long", "long", "Timestamp" -> 0;
                default -> "";
            };
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        return value;
    }

    private Object read(Field field) {
        try {
            field.setAccessible(true);
            return field.get(this);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to read player field: " + field.getName(), exception);
        }
    }

    private static Property property(String column) {
        Property property = PROPERTIES_BY_COLUMN.get(column);
        if (property == null) {
            throw new IllegalArgumentException("Unknown player field: " + column);
        }
        return property;
    }

    private static Property propertyForSetter(Class<?> type, String setterMethod) {
        return properties(type).stream()
                .filter(property -> setterNames(property.property()).contains(setterMethod))
                .findFirst()
                .orElse(null);
    }

    private static SerializedLambda serializedLambda(Serializable setter) {
        try {
            Method writeReplace = setter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replacement = writeReplace.invoke(setter);
            if (replacement instanceof SerializedLambda lambda) {
                return lambda;
            }
            throw new IllegalArgumentException("Unsupported setter method reference");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Unable to inspect setter method reference", exception);
        }
    }

    private static Class<?> declaringClass(SerializedLambda lambda) {
        try {
            return Class.forName(lambda.getImplClass().replace('/', '.'));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Unable to resolve setter declaring class: "
                    + lambda.getImplClass(), exception);
        }
    }

    private static Set<String> setterNames(String property) {
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Set.of("set" + suffix, "set" + suffix + "Millis", "set" + suffix + "Value");
    }

    private static Object convertValue(Object value, Class<?> target) {
        if (value == null || target.isInstance(value)) {
            return value;
        }
        if (target == String.class) return String.valueOf(value);
        if (target == boolean.class || target == Boolean.class) {
            return value instanceof Boolean b ? b : "1".equals(value.toString()) || Boolean.parseBoolean(value.toString());
        }
        if (target == int.class || target == Integer.class) return Integer.valueOf(value.toString());
        if (target == long.class || target == Long.class) return Long.valueOf(value.toString());
        if (target == Timestamp.class) return new Timestamp(Long.parseLong(value.toString()));
        return value;
    }

    private void captureExpectedVersion() {
        if (expectedVersion == null && version != null) {
            expectedVersion = version;
        }
    }

    private static List<Property> properties(Class<?> type) {
        List<Property> result = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.exist()) {
                continue;
            }
            TableId tableId = field.getAnnotation(TableId.class);
            String column = tableId != null && !tableId.value().isBlank()
                    ? tableId.value()
                    : tableField != null && !tableField.value().isBlank()
                    ? tableField.value()
                    : camelToSnake(field.getName());
            result.add(new Property(field, field.getName(), column));
        }
        return List.copyOf(result);
    }

    private static String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private record Property(Field field, String property, String column) {
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

    private static String textValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Timestamp timestamp) {
            return String.valueOf(timestamp.getTime());
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        return String.valueOf(value);
    }

}
