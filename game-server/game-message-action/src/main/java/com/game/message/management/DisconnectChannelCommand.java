package com.game.message.management;

import com.game.message.actor.gateway.GatewayControlMessage;

public record DisconnectChannelCommand(
        String requestId,
        long playerId,
        String reason
) implements GatewayControlMessage {
}
