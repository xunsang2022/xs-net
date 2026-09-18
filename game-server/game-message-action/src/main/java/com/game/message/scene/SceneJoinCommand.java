package com.game.message.scene;

import com.game.message.actor.logic.SceneMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 将玩家加入场景成员列表的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3006)
public record SceneJoinCommand(@ProtoField(number = 1) Long playerId) implements SceneMessage {
}
