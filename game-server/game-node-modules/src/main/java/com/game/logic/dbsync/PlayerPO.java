package com.game.logic.dbsync;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.sql.Timestamp;
import java.util.Map;

/** ActiveRecord mapping for the existing users table. */
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class PlayerPO extends Model<PlayerPO> {

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
        player.name = value(fields, "name", "player-" + record.playerId());
        player.email = value(fields, "email", "");
        player.online = intValue(fields, "online", 0);
        player.lastLoginAt = timestampValue(fields.get("last_login_at"));
        player.updatedAt = timestampValue(fields.get("updated_at"));
        player.version = intValue(fields, "version", Math.toIntExact(record.newVersion()));
        player.expectedVersion = Math.toIntExact(record.expectedVersion());
        return player;
    }

    public Map<String, String> toStateFields() {
        return Map.of(
                "id", String.valueOf(id),
                "name", name == null ? "" : name,
                "email", email == null ? "" : email,
                "online", String.valueOf(online == null ? 0 : online),
                "last_login_at", timestampString(lastLoginAt),
                "updated_at", timestampString(updatedAt),
                "version", String.valueOf(version == null ? 1 : version));
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

    private static String timestampString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.util.Date date) {
            return String.valueOf(date.getTime());
        }
        return String.valueOf(value);
    }

}
