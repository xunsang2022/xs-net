package com.game.message;

import com.game.message.rpc.GameRpcEnvelopeFactory;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.DbSyncLoadResultMessage;
import com.game.protocol.pb.PlayerEnter;
import com.game.protocol.pb.SC_Logout;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessage;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.serializer.NetMessageSerializer;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RpcTransportTest {

    @Before
    public void setUp() {
        MessageAble.initialize(GameRpcMessages.INSTANCE, new GameRpcEnvelopeFactory());
    }

    @Test
    public void loginToLogicTransport() throws Exception {
        PlayerEnter enter = PlayerEnter.newBuilder()
                .setPlayerId(42L)
                .setPlayerName("alice")
                .setChannelId("ch-1")
                .build();

        RpcMessage decoded = transport(enter);
        assertEquals(3003, decoded.rpcNum());
        assertEquals(enter, decoded.payload());
    }

    @Test
    public void logicToGatewayTransport() throws Exception {
        SC_Logout logout = SC_Logout.newBuilder()
                .setPlayerId("42")
                .setSessionId("s-1")
                .build();

        RpcMessage decoded = transport(logout);
        assertEquals(1003, decoded.rpcNum());
        assertEquals(logout, decoded.payload());
    }

    @Test
    public void dbToLogicTransport() throws Exception {
        DbSyncLoadResultMessage result = DbSyncLoadResultMessage.newBuilder()
                .setPlayerId(42L)
                .setSuccess(true)
                .putAllFields(Map.of("name", "alice"))
                .build();

        RpcMessage decoded = transport(result);
        assertEquals(3102, decoded.rpcNum());
        assertEquals(result, decoded.payload());
    }

    private static RpcMessage transport(com.google.protobuf.MessageLite payload) throws Exception {
        NetMessage netMessage = MessageAble.toNetMessage(GameRpcMessages.wrap(payload));
        NetMessageSerializer serializer = new NetMessageSerializer();
        Object decoded = serializer.fromBinary(serializer.toBinary(netMessage));
        assertTrue(decoded instanceof RpcMessage);
        return (RpcMessage) decoded;
    }
}
