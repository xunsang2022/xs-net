package com.game.message;

import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.CS_LOGIN;
import com.game.protocol.pb.CS_LOG_REPORT;
import com.game.protocol.pb.CS_Logout;
import com.game.protocol.pb.DbSyncLoadMessage;
import com.game.protocol.pb.DbSyncLoadResultMessage;
import com.game.protocol.pb.DbSyncMessage;
import com.game.protocol.pb.LogRecordMessage;
import com.game.protocol.pb.PlayerEnter;
import com.game.protocol.pb.SC_LOGIN;
import com.game.protocol.pb.SC_LOG_REPORT;
import com.game.protocol.pb.SC_Logout;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageSpec;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GameMessagesGoldenTest {

    @Test
    public void goldenMessagesMatchNativeEncoding() throws Exception {
        assertGolden("LogRecordMessage", LogRecordMessage.newBuilder()
                .setLevel(2)
                .setType(3)
                .setMessage("hello")
                .setPlayerId(42L)
                .build());
        assertGolden("CS_LOGIN", CS_LOGIN.newBuilder()
                .setPlayerId("1001")
                .setPlayerName("alice")
                .setSessionId("s-1")
                .build());
        assertGolden("CS_Logout", CS_Logout.newBuilder()
                .setPlayerId("1001")
                .setSessionId("s-2")
                .build());
        assertGolden("SC_LOGIN", SC_LOGIN.newBuilder()
                .setResult(0)
                .setPlayerId("1001")
                .setPlayerName("alice")
                .setToken("token-1")
                .build());
        assertGolden("SC_Logout", SC_Logout.newBuilder()
                .setPlayerId("1001")
                .setSessionId("s-3")
                .build());
        assertGolden("CS_LOG_REPORT", CS_LOG_REPORT.newBuilder()
                .setSessionId("s-4")
                .setLogLevel(1)
                .setLogType(2)
                .setContent("{\"k\":\"v\"}")
                .build());
        assertGolden("SC_LOG_REPORT", SC_LOG_REPORT.newBuilder()
                .setSessionId("s-5")
                .setResult(0)
                .setLogId(77L)
                .build());
        assertGolden("PlayerEnter", PlayerEnter.newBuilder()
                .setPlayerId(1001L)
                .setPlayerName("alice")
                .setChannelId("ch-1")
                .build());
        assertGolden("DbSyncMessage", DbSyncMessage.newBuilder()
                .setPlayerId(1001L)
                .setOp(0)
                .build());
        assertGolden("DbSyncLoadMessage", DbSyncLoadMessage.newBuilder()
                .setPlayerId(1001L)
                .build());
        assertGolden("DbSyncLoadResultMessage", DbSyncLoadResultMessage.newBuilder()
                .setPlayerId(1001L)
                .setSuccess(true)
                .putAllFields(Map.of("name", "alice"))
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
        assertEquals(11, GameRpcMessages.INSTANCE.all().size());
        assertNotNull(GameRpcMessages.INSTANCE.find(1000));
        assertEquals(1003, GameRpcMessages.INSTANCE.find(1003).rpcNum());
    }

    @Test
    public void optionalPresenceIsPreserved() {
        CS_LOGIN absent = CS_LOGIN.newBuilder().setPlayerId("1").build();
        assertFalse(absent.hasSessionId());
        assertFalse(absent.hasPlayerName());

        CS_LOGIN present = CS_LOGIN.newBuilder().setPlayerId("1").setPlayerName("alice").build();
        assertTrue(present.hasPlayerName());
        assertEquals("alice", present.getPlayerName());
    }

    @Test
    public void mapFieldRoundTrip() throws Exception {
        byte[] golden = readHex("DbSyncLoadResultMessage.hex");
        DbSyncLoadResultMessage parsed = DbSyncLoadResultMessage.parseFrom(golden);
        assertEquals(1001L, parsed.getPlayerId());
        assertTrue(parsed.getSuccess());
        assertEquals(Map.of("name", "alice"), parsed.getFieldsMap());
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
