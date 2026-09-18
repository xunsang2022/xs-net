package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 将玩家移出战斗实例的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3012)
public record BattleLeaveCommand(
        @ProtoField(number = 1) Long playerId
) implements BattleMessage {
}
