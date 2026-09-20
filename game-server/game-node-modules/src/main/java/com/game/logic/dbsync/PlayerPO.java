package com.game.logic.dbsync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.game.logic.context.LogicContext;
import com.xs.core.model.XsModel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.sql.Timestamp;
import java.util.Map;

/**
 * 玩家 users 表的 ActiveRecord 映射，同时作为玩家运行时状态载体。
 *
 * <p>通用的属性方法引用、脏字段跟踪和 Redis Hash 转换由 xs-net 的
 * {@link XsModel} 提供；本类只保留玩家字段、乐观锁和持久化业务。</p>
 */
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true, exclude = "expectedVersion")
public class PlayerPO extends XsModel<PlayerPO> {

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

    @Override
    protected void beforePropertySet(String column) {
        captureExpectedVersion();
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

    public void fromHash(Map<?, ?> fields) {
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null || !hasProperty(field)) {
                continue;
            }
            Property property = property(field);
            Object converted = convertValue(value, property.field().getType());
            writeField(property.field(), converted);
        }
    }

    public static Map<String, Object> redisValues(Map<String, String> fields) {
        return redisValues(PlayerPO.class, fields);
    }

    private void captureExpectedVersion() {
        if (expectedVersion == null && version != null) {
            expectedVersion = version;
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

}
