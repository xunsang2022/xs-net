package com.game.message.s2c;

import com.game.message.annotation.ProtoField;
import com.game.message.actor.S2C;
public record LogoutResponse(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String sessionId
) implements S2C {
}
