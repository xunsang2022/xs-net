package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 战斗服内部攻击指令，携带攻击者、目标及玩家维度的递增序号。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3015)
public record BattleAttackCommand(
        @ProtoField(number = 1) Long attackerPlayerId,
        @ProtoField(number = 2) Long targetPlayerId,
        @ProtoField(number = 3) Long clientSequence
) implements BattleMessage {
}
