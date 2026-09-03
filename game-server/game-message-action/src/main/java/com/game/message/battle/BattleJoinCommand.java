package com.game.message.battle;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.INTERNAL, number = 3011)
public record BattleJoinCommand(
        @ProtoField(number = 1) Long playerId
) implements BattleMessage {
}
