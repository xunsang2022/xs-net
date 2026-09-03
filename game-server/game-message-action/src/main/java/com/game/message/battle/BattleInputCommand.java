package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.INTERNAL, number = 3013)
public record BattleInputCommand(
        @ProtoField(number = 1) Long playerId,
        @ProtoField(number = 2) Long clientSequence,
        @ProtoField(number = 3) Integer directionX,
        @ProtoField(number = 4) Integer directionY
) implements BattleMessage {
}
