package com.game.logic.actor.player.db;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.game.eventsourcing.persistence.GameModel;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.ProtoRecord;
import com.xs.kernel.model.RedisKey;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.sql.Timestamp;

/**
 * 玩家 users 表的 MyBatis-Plus 映射，同时作为玩家运行时状态载体。
 *
 * <p>属性方法引用、脏字段跟踪和通用 Hash 转换由 xs-net 的
 * {@link com.xs.kernel.model.XsModel} 提供；本类只声明玩家数据和乐观锁字段。</p>
 */
@RedisKey("playerActor:")
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true, exclude = "expectedVersion")
@ProtoRecord(classType = "com.game.message.s2c.PlayerInfoData", number = 2102)
public class PlayerPO extends GameModel<PlayerPO> {


    @TableId(value = "id", type = IdType.INPUT)
    @ProtoField(number = 1)
    private Long id;
    @ProtoField(number = 2)
    private String name;
    @ProtoField(number = 3)
    private String email;
    @ProtoField(number = 4)
    private Integer online;
    @ProtoField(number = 5)
    private Timestamp lastLoginAt;
    @ProtoField(number = 6)
    private Timestamp updatedAt;
    @ProtoField(number = 7)
    private Integer version;

    @TableField(exist = false)
    private Integer expectedVersion;

    @Override
    protected void beforePropertySet(String column) {
        if (expectedVersion == null && version != null) {
            expectedVersion = version;
        }
    }

    public long getVersionValue() {
        return version == null ? 0L : version;
    }
}
