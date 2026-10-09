package com.game.logic.actor.player;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.game.eventsourcing.GameModel;
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
@TableName("users")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true, exclude = "expectedVersion")
public class PlayerPO extends GameModel<PlayerPO> {

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
