package com.game.client;

import com.game.protocol.pb.SC_Logout;
import com.xs.net.message.pb.NetMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogoutResponseTest {

    @Test
    void decodesLogoutResponseFromNetMessageFrame() throws Exception {
        SC_Logout expected = SC_Logout.newBuilder()
                .setPlayerId("10001")
                .setSessionId("session-1")
                .build();
        NetMessage message = NetMessage.newBuilder()
                .setRpcNum(1003)
                .setData(expected.toByteString())
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NettyFrameCodec.write(new DataOutputStream(bytes), message.toByteArray());

        NetMessage decoded = NetMessage.parseFrom(
                NettyFrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        SC_Logout logout = SC_Logout.parseFrom(decoded.getData());

        assertEquals(1003, decoded.getRpcNum());
        assertEquals(expected, logout);
    }

    @Test
    void logoutRequiresTcpConnection() {
        GameClientApplication.ClientSettings settings = new GameClientApplication.ClientSettings(
                "http://127.0.0.1:8080", "127.0.0.1", 8082, 10001, "game-client", java.time.Duration.ofSeconds(1));
        GameTestClient client = new GameTestClient(settings);

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> client.logout(new LoginSession(10001, "game-client", "token")));

        assertEquals("Connect to the gateway before logging out", error.getMessage());
    }
}
