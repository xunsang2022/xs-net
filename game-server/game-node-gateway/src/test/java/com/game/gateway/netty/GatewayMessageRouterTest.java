package com.game.gateway.netty;

import com.game.message.c2s.LoginRequest;
import com.xs.net.message.RequestContext;
import com.xs.net.message.pb.NetMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayMessageRouterTest {

    @Test
    void authenticatedChannelOverridesClientEnvelopeChannel() {
        NetMessage message = NetMessage.newBuilder()
                .setPlayerId("1")
                .setChannelId("client-session")
                .build();

        RequestContext context = GatewayMessageRouter.authenticatedContext(message, "authenticated-channel");

        assertEquals("1", context.playerId());
        assertEquals("authenticated-channel", context.channelId());
    }

}
