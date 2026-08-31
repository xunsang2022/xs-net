package com.game.message.management;

import com.game.message.actor.logic.GMMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.INTERNAL, number = 3004)
public record GmKickPlayerRequest(
        @ProtoField(number = 1) String requestId,
        @ProtoField(number = 2) Long playerId,
        @ProtoField(number = 3) String operator,
        @ProtoField(number = 4) String reason,
        @ProtoField(number = 5) Long issuedAt
) implements GMMessage {
}
