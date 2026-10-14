package com.game.message.s2c;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.ProtoRecord;

import java.sql.Timestamp;

/**
 * PlayerInfoData 协议记录的字段声明载体。
 *
 * <p>本类不承载业务逻辑，仅用于在 game-message-action 内声明 {@link ProtoRecord}，
 * 由 xs-net-rpc-maven-plugin 扫描本类自身生成协议记录
 * {@code com.game.message.s2c.PlayerInfoData}（RPC 2102，ACTOR_MODEL）。</p>
 *
 * <p>字段与 game-node-modules 的 {@code com.game.logic.actor.player.db.PlayerPO} 上标注
 * {@code @ProtoField} 的字段完全一致（数量、名称、编号一一对应）；其中 Timestamp 字段
 * 由插件映射为 int64 毫秒。</p>
 */
@ProtoRecord(classType = "com.game.message.s2c.PlayerInfoData", number = 2102)
public class PlayerInfoProto {

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
}
