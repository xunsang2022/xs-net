package com.xs.net.message;

import com.google.protobuf.ByteString;
import com.xs.net.message.pb.NetMessage;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class NetMessageGoldenTest extends TestCase {

    public void testNetMessageMatchesGolden() throws Exception {
        byte[] golden = readHex("NetMessage.hex");
        NetMessage actual = NetMessage.newBuilder()
                .setRpcNum(1000)
                .setData(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .setErrorCode(7)
                .setSessionId(11)
                .setUserId(22)
                .setUserIp("1.2.3.4")
                .build();
        assertTrue(Arrays.equals(golden, actual.toByteArray()));
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
