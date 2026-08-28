package com.game.message.c2s;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;
import com.game.message.routing.ChannelRoutable;
import com.game.message.routing.PlayerRoutable;
import com.game.message.actor.logic.PlayerMessage;

@Rpc(
        kind = Rpc.Kind.REQUEST,
        requestNumber = 1001,
        responseNumber = 1003
)
public record LogoutRequest(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String sessionId
) implements PlayerMessage, PlayerRoutable, ChannelRoutable {
    @Override
    public String channelId() {
        return sessionId;
    }
}
