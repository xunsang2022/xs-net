package com.game.gateway.netty;

import com.game.message.c2s.LoginRequest;
import com.game.message.c2s.LogoutRequest;
import com.game.message.c2s.PlayerEnterCommand;
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

    @Test
    void authenticatedChannelOverridesLoginSessionId() {
        RequestContext context = RequestContext.root("1", "authenticated-channel", java.time.Duration.ofSeconds(3));
        LoginRequest login = new LoginRequest("1", "alice", "1");

        String channelId = GatewayMessageRouter.authenticatedChannelId(context, login.sessionId());
        PlayerEnterCommand command = new PlayerEnterCommand(
                Long.parseLong(login.playerId()), login.playerName(), channelId);

        assertEquals("authenticated-channel", command.channelId());
    }

    @Test
    void authenticatedChannelOverridesLogoutSessionId() {
        RequestContext context = RequestContext.root("1", "authenticated-channel", java.time.Duration.ofSeconds(3));
        LogoutRequest logout = new LogoutRequest("1", "1");

        String channelId = GatewayMessageRouter.authenticatedChannelId(context, logout.sessionId());

        assertEquals("authenticated-channel", channelId);
    }
}
