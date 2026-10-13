package com.game.message;

import com.game.message.s2c.LoginResponse;
import com.game.message.s2c.LogoutResponse;
import com.game.message.s2c.PlayerInfoData;
import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleEndCommand;
import com.game.message.battle.BattleInputCommand;
import com.game.message.battle.BattleJoinCommand;
import com.game.message.battle.BattleLeaveCommand;
import com.game.message.management.GmKickPlayerCommand;
import com.game.message.management.GmKickPlayerRequest;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.SC_Login;
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
    public void logicToGatewayTransport() throws Exception {
        LogoutResponse logout = new LogoutResponse("42", "s-1");

        Object decoded = transport(logout);
        assertTrue(decoded instanceof NetMessage);
        assertEquals(1003, ((NetMessage) decoded).getRpcNum());
    }

    @Test
    public void loginResponseTransportPreservesPlayerInfo() throws Exception {
        LoginResponse login = new LoginResponse(0, "1001",
                new PlayerInfoData(1001L, "alice", "a@example.com", 1, 1700000000000L, 1700000001000L, 2));

        NetMessage decoded = (NetMessage) transport(login);
        assertEquals(1002, decoded.getRpcNum());
        SC_Login payload = SC_Login.parseFrom(decoded.getData());
        assertEquals(0, payload.getResult());
        assertEquals("1001", payload.getToken());
        assertTrue(payload.hasPlayerInfo());
        assertEquals(1001L, payload.getPlayerInfo().getId());
        assertEquals("alice", payload.getPlayerInfo().getName());
        assertEquals("a@example.com", payload.getPlayerInfo().getEmail());
        assertEquals(1, payload.getPlayerInfo().getOnline());
        assertEquals(1700000000000L, payload.getPlayerInfo().getLastLoginAt());
        assertEquals(1700000001000L, payload.getPlayerInfo().getUpdatedAt());
        assertEquals(2, payload.getPlayerInfo().getVersion());
        assertEquals(login, GameRpcMessages.INSTANCE.find(1002).decode(payload));
    }

    @Test
    public void managementMessagesTransportBetweenNodes() throws Exception {
        assertTrue(transport(new GmKickPlayerRequest("request-1", 42L, "operator", "reason", 100L)) instanceof NetMessage);
        assertTrue(transport(new GmKickPlayerCommand("request-1", "operator", "reason", 100L)) instanceof NetMessage);
    }

    @Test
    public void battleMessagesTransportBetweenNodes() throws Exception {
        assertTrue(transport(new BattleCreateCommand("battle-1")) instanceof NetMessage);
        assertTrue(transport(new BattleJoinCommand(42L)) instanceof NetMessage);
        assertTrue(transport(new BattleLeaveCommand(42L)) instanceof NetMessage);
        assertTrue(transport(new BattleInputCommand(42L, 7L, -1, 1)) instanceof NetMessage);
        assertTrue(transport(new BattleEndCommand("finished")) instanceof NetMessage);
    }

    private static Object transport(Object message) throws Exception {
        NetMessage netMessage = MessageAble.toNetMessage(message);
        NetMessageSerializer serializer = new NetMessageSerializer();
        return serializer.fromBinary(serializer.toBinary(netMessage));
    }
}
