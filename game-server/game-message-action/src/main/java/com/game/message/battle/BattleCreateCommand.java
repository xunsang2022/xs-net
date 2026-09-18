package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 创建指定战斗实例的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3010)
public record BattleCreateCommand(
        @ProtoField(number = 1) String battleId
) implements BattleMessage {
}
