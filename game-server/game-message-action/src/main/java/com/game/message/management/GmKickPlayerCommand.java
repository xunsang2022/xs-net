package com.game.message.management;

import com.game.message.actor.logic.PlayerMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** GM 审核通过后，通知玩家 Actor 执行踢出操作的内部指令。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3005)
public record GmKickPlayerCommand(
        @ProtoField(number = 1) String requestId,
        @ProtoField(number = 2) String operator,
        @ProtoField(number = 3) String reason,
        @ProtoField(number = 4) Long issuedAt
) implements PlayerMessage {
}
