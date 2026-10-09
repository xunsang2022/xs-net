package com.game.message.s2c;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;
import com.game.message.actor.S2C;

/** 服务端返回玩家登出结果的下行消息。 */
@Rpc(kind = Rpc.Kind.MESSAGE, number = 1003)
public record LogoutResponse(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String sessionId
) implements S2C {
}
