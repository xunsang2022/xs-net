package com.game.message;

import com.game.message.s2c.LogoutResponse;
import com.game.message.c2s.PlayerEnterCommand;
import com.game.message.rpc.GameRpcMessages;
import com.xs.net.message.MessageAble;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.serializer.NetMessageSerializer;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RpcTransportTest {

    @Before
    public void setUp() {
        MessageAble.initialize(GameRpcMessages.INSTANCE);
    }

    @Test
    public void loginToLogicTransport() throws Exception {
        PlayerEnterCommand enter = new PlayerEnterCommand(42L, "alice", "ch-1");

        Object decoded = transport(enter);
        assertTrue(decoded instanceof PlayerEnterCommand);
        assertEquals(enter, decoded);
    }

    @Test
    public void logicToGatewayTransport() throws Exception {
        LogoutResponse logout = new LogoutResponse("42", "s-1");

        Object decoded = transport(logout);
        assertTrue(decoded instanceof LogoutResponse);
        assertEquals(logout, decoded);
    }

    private static Object transport(Object message) throws Exception {
        NetMessage netMessage = MessageAble.toNetMessage(message);
        NetMessageSerializer serializer = new NetMessageSerializer();
        return serializer.fromBinary(serializer.toBinary(netMessage));
    }
}
