package com.game.message.scene;

import com.game.message.actor.logic.SceneMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 将玩家从场景成员列表移除的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3007)
public record SceneLeaveCommand(@ProtoField(number = 1) Long playerId) implements SceneMessage {
}
