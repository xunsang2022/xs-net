package com.game.message.s2c;

import com.game.message.actor.S2C;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 服务端返回玩家登录结果及玩家基础信息的下行消息。 */
@Rpc(kind = Rpc.Kind.MESSAGE, number = 1002)
public record LoginResponse(
        @ProtoField(number = 1) Integer result,
        @ProtoField(number = 2) String playerId,
        @ProtoField(number = 3) String playerName,
        @ProtoField(number = 4) String token
) implements S2C {
}
