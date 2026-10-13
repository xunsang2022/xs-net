package com.game.gateway.netty;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UdpReliableLayerTest {

    private static UdpReliableLayer newLayer(int window, UdpReliableLayer.PacketSender sender, List<byte[]> delivered) {
        return new UdpReliableLayer(window, 42, sender, delivered::add, System::currentTimeMillis);
    }

    @Test
    void data_deliversInOrder() {
        List<byte[]> delivered = new ArrayList<>();
        UdpReliableLayer layer = newLayer(32, (t, s, ack, pl) -> {}, delivered);

        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 1, 42, "a".getBytes(StandardCharsets.UTF_8)));
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 2, 42, "b".getBytes(StandardCharsets.UTF_8)));
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 3, 42, "c".getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, delivered.size());
        assertEquals("a", new String(delivered.get(0), StandardCharsets.UTF_8));
        assertEquals("b", new String(delivered.get(1), StandardCharsets.UTF_8));
        assertEquals("c", new String(delivered.get(2), StandardCharsets.UTF_8));
        assertEquals(3, layer.contiguous());
    }

    @Test
    void duplicate_dropped() {
        List<byte[]> delivered = new ArrayList<>();
        UdpReliableLayer layer = newLayer(32, (t, s, ack, pl) -> {}, delivered);

        byte[] payload = "dup".getBytes(StandardCharsets.UTF_8);
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 1, 42, payload));
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 1, 42, payload));
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 1, 42, payload));

        assertEquals(1, delivered.size());
    }

    @Test
    void outOfOrder_buffered_thenDelivered() {
        List<byte[]> delivered = new ArrayList<>();
        UdpReliableLayer layer = newLayer(32, (t, s, ack, pl) -> {}, delivered);

        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 1, 42, "1".getBytes(StandardCharsets.UTF_8)));
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 3, 42, "3".getBytes(StandardCharsets.UTF_8)));
        // seq=3 乱序，未交付
        assertEquals(1, delivered.size());
        // seq=2 到达，补齐 1,2,3
        layer.onPacket(new UdpReliableLayer.Packet(UdpReliableLayer.TYPE_DATA, 2, 42, "2".getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, delivered.size());
        assertEquals("1", new String(delivered.get(0), StandardCharsets.UTF_8));
        assertEquals("2", new String(delivered.get(1), StandardCharsets.UTF_8));
        assertEquals("3", new String(delivered.get(2), StandardCharsets.UTF_8));
    }

    @Test
    void encodeDecode_roundTrip() {
        byte[] payload = "hello-udp".getBytes(StandardCharsets.UTF_8);
        io.netty.buffer.ByteBuf buf = UdpReliableLayer.encode(UdpReliableLayer.TYPE_DATA, 7, 3, payload);
        UdpReliableLayer.Packet packet = UdpReliableLayer.decode(buf);

        assertEquals(UdpReliableLayer.TYPE_DATA, packet.type());
        assertEquals(7, packet.seq());
        assertEquals(3, packet.random());
        assertEquals("hello-udp", new String(packet.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void decode_invalidHeaderCrc_returnsNull() {
        io.netty.buffer.ByteBuf buf = UdpReliableLayer.encode(UdpReliableLayer.TYPE_DATA, 1, 0, null);
        buf.setByte(5, (byte) 0x00); // 修改 packetId，保持 length 不变但破坏 headCRC
        assertNull(UdpReliableLayer.decode(buf));
        buf.release();
    }
}
