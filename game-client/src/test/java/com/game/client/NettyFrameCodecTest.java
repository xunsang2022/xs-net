package com.game.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyFrameCodecTest {

    @Test
    void writesAndReadsLengthPrefixedFrame() throws IOException {
        byte[] expected = {1, 2, 3, 4};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        NettyFrameCodec.write(new DataOutputStream(bytes), expected);

        byte[] actual = NettyFrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertArrayEquals(expected, actual);
    }

    @Test
    void rejectsOversizedIncomingFrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(NettyFrameCodec.MAX_FRAME_LENGTH + 1);
        }

        assertThrows(
                IOException.class,
                () -> NettyFrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    void rejectsIncompleteIncomingFrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(3);
            output.write(new byte[]{1, 2});
        }

        assertThrows(
                IOException.class,
                () -> NettyFrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }
}
