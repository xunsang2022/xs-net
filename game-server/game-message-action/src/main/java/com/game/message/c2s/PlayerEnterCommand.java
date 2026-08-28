package com.game.message.c2s;

import com.game.message.annotation.Rpc;
import com.game.message.annotation.ProtoField;
import com.game.message.routing.ChannelRoutable;
import com.game.message.routing.PlayerRoutable;
import com.game.message.actor.logic.PlayerMessage;

@Rpc(kind = Rpc.Kind.INTERNAL, number = 3003)
public record PlayerEnterCommand(
        @ProtoField(number = 1) Long playerId,
        @ProtoField(number = 2) String playerName,
        @ProtoField(number = 3) String channelId
) implements PlayerMessage, PlayerRoutable, ChannelRoutable {
}
