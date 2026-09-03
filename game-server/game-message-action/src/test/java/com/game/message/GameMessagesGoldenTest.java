package com.game.message;

import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.CS_Login;
import com.game.protocol.pb.CS_LogReport;
import com.game.protocol.pb.CS_Logout;
import com.game.protocol.pb.LogRecordCommand;
import com.game.protocol.pb.PlayerEnterCommand;
import com.game.protocol.pb.RpcRoute;
import com.game.protocol.pb.SC_Login;
import com.game.protocol.pb.SC_LogReport;
import com.game.protocol.pb.SC_Logout;
import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageSpec;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GameMessagesGoldenTest {

    @Test
    public void goldenMessagesMatchNativeEncoding() throws Exception {
        assertGolden("LogRecordMessage", LogRecordCommand.newBuilder()
                .setLevel(2)
                .setType(3)
                .setMessage("hello")
                .setPlayerId(42L)
                .build());
        assertGolden("CS_LOGIN", CS_Login.newBuilder()
                .setPlayerId("1001")
                .setPlayerName("alice")
                .setSessionId("s-1")
                .build());
        assertGolden("CS_Logout", CS_Logout.newBuilder()
                .setPlayerId("1001")
                .setSessionId("s-2")
                .build());
        assertGolden("SC_LOGIN", SC_Login.newBuilder()
                .setResult(0)
                .setPlayerId("1001")
                .setPlayerName("alice")
                .setToken("token-1")
                .build());
        assertGolden("SC_Logout", SC_Logout.newBuilder()
                .setPlayerId("1001")
                .setSessionId("s-3")
                .build());
        assertGolden("CS_LOG_REPORT", CS_LogReport.newBuilder()
                .setSessionId("s-4")
                .setLogLevel(1)
                .setLogType(2)
                .setContent("{\"k\":\"v\"}")
                .build());
        assertGolden("SC_LOG_REPORT", SC_LogReport.newBuilder()
                .setSessionId("s-5")
                .setResult(0)
                .setLogId(77L)
                .build());
        assertGolden("PlayerEnter", PlayerEnterCommand.newBuilder()
                .setPlayerId(1001L)
                .setPlayerName("alice")
                .setChannelId("ch-1")
                .build());
    }

    @Test
    public void registryIsCompleteAndUnique() {
        Set<Integer> rpcNums = new HashSet<>();
        for (RpcMessageSpec<? extends MessageLite> spec : GameRpcMessages.INSTANCE.all()) {
            assertTrue(rpcNums.add(spec.rpcNum()));
            assertNotNull(spec.messageClass());
            assertNotNull(spec.parser());
        }
        assertEquals(Set.of(
                999, 1000, 1001, 1002, 1003, 2100, 2101,
                3003, 3004, 3005, 3006, 3007, 3008,
                3010, 3011, 3012, 3013, 3014
        ), rpcNums);
        assertNotNull(GameRpcMessages.INSTANCE.find(1000));
        assertEquals(1003, GameRpcMessages.INSTANCE.find(1003).rpcNum());
    }

    @Test
    public void routerEnumRegistersGroupMessages() {
        assertEquals(1001, RpcRoute.RPC_CS_LOGOUT.getNumber());
        assertEquals(1003, RpcRoute.RPC_SC_LOGOUT.getNumber());
        assertEquals(3003, RpcRoute.RPC_PLAYER_ENTER_COMMAND.getNumber());
        assertEquals(3004, RpcRoute.RPC_CS_GM_KICK_PLAYER.getNumber());
        assertEquals(3005, RpcRoute.RPC_GM_KICK_PLAYER_COMMAND.getNumber());
        assertEquals(3006, RpcRoute.RPC_SCENE_JOIN_COMMAND.getNumber());
        assertEquals(3007, RpcRoute.RPC_SCENE_LEAVE_COMMAND.getNumber());
        assertEquals(3008, RpcRoute.RPC_PLAYER_SCENE_CHANGED.getNumber());
        assertEquals(RpcRoute.RPC_CS_LOGOUT, RpcRoute.forNumber(1001));
        assertEquals(RpcRoute.RPC_SC_LOGOUT, RpcRoute.forNumber(1003));
        assertEquals(RpcRoute.RPC_PLAYER_ENTER_COMMAND, RpcRoute.forNumber(3003));
        assertEquals(RpcRoute.RPC_CS_GM_KICK_PLAYER, RpcRoute.forNumber(3004));
        assertEquals(RpcRoute.RPC_PLAYER_SCENE_CHANGED, RpcRoute.forNumber(3008));
        assertEquals(RpcRoute.RPC_BATTLE_CREATE_COMMAND, RpcRoute.forNumber(3010));
        assertEquals(RpcRoute.RPC_BATTLE_INPUT_COMMAND, RpcRoute.forNumber(3013));
    }

    @Test
    public void optionalPresenceIsPreserved() {
        CS_Login absent = CS_Login.newBuilder().setPlayerId("1").build();
        assertFalse(absent.hasSessionId());
        assertFalse(absent.hasPlayerName());

        CS_Login present = CS_Login.newBuilder().setPlayerId("1").setPlayerName("alice").build();
        assertTrue(present.hasPlayerName());
        assertEquals("alice", present.getPlayerName());
    }

    private static void assertGolden(String name, MessageLite message) throws Exception {
        assertArrayEquals(readHex(name + ".hex"), message.toByteArray());
    }

    private static byte[] readHex(String name) throws Exception {
        Path path = Paths.get("src/test/resources/golden").resolve(name);
        String hex = Files.readString(path, StandardCharsets.US_ASCII).trim();
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
