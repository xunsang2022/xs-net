package com.xs.net.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketCodecTest {
    @Test
    void roundTripsHeaderAndBody() {
        byte[] body = new byte[]{1, 2, 3};
        PacketFrame packet = PacketCodec.decode(PacketCodec.encode(PacketId.DATA, 7, 42, body));

        assertEquals(PacketId.DATA, packet.packetId());
        assertEquals(7, packet.sendSeq());
        assertEquals(42, packet.random());
        assertArrayEquals(body, packet.body());
    }

    @Test
    void rejectsCorruptedHeaderAndBody() {
        byte[] headerCorrupted = PacketCodec.encode(PacketId.DATA, 1, 2, new byte[]{9});
        headerCorrupted[6] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> PacketCodec.decode(headerCorrupted));

        byte[] bodyCorrupted = PacketCodec.encode(PacketId.DATA, 1, 2, new byte[]{9});
        bodyCorrupted[PacketCodec.HEADER_BYTES] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> PacketCodec.decode(bodyCorrupted));
    }
}
