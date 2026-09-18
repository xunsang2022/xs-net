package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 通知战斗实例结束，并携带结束原因的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3014)
public record BattleEndCommand(
        @ProtoField(number = 1) String reason
) implements BattleMessage {
}
