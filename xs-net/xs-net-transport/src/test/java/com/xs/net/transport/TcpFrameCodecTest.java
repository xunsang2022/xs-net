package com.xs.net.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpFrameCodecTest {
    @Test
    void roundTripsLengthPrefixedPayload() {
        TcpFrameCodec codec = new TcpFrameCodec();
        byte[] payload = new byte[]{1, 2, 3};
        assertArrayEquals(payload, codec.decode(codec.encode(payload)));
    }

    @Test
    void rejectsMismatchedLength() {
        TcpFrameCodec codec = new TcpFrameCodec();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[]{0, 0, 0, 5, 1}));
    }

    @Test
    void rejectsPayloadAboveConfiguredMaximum() {
        TcpFrameCodec codec = new TcpFrameCodec(2);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new byte[]{1, 2, 3}));
    }
}
