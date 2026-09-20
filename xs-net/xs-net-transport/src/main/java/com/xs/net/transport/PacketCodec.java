package com.xs.net.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * XS packet codec.
 *
 * <pre>
 * length(4) | packetId(2) | sendSeq(4) | random(4) |
 * headCRC(2) | bodyCRC(2) | body(N)
 * </pre>
 *
 * length is the body length. CRC is CRC-16/CCITT-FALSE, big endian.
 */
public final class PacketCodec {
    public static final int HEADER_BYTES = 18;
    public static final int BODY_LENGTH_OFFSET = 0;
    public static final int PACKET_ID_OFFSET = 4;
    public static final int SEND_SEQ_OFFSET = 6;
    public static final int RANDOM_OFFSET = 10;
    public static final int HEAD_CRC_OFFSET = 14;
    public static final int BODY_CRC_OFFSET = 16;
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    private PacketCodec() {
    }

    public static byte[] encode(int packetId, long sendSeq, long random, byte[] body) {
        validate(packetId, sendSeq, random, body, DEFAULT_MAX_BODY_BYTES);
        byte[] result = new byte[HEADER_BYTES + body.length];
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(body.length);
        buffer.putShort((short) packetId);
        buffer.putInt((int) sendSeq);
        buffer.putInt((int) random);
        buffer.putShort((short) crc16(result, 0, HEAD_CRC_OFFSET));
        buffer.putShort((short) crc16(body, 0, body.length));
        System.arraycopy(body, 0, result, HEADER_BYTES, body.length);
        return result;
    }

    public static byte[] encode(int packetId, long sendSeq, long random, byte[] body, int maxBodyBytes) {
        validate(packetId, sendSeq, random, body, maxBodyBytes);
        byte[] result = new byte[HEADER_BYTES + body.length];
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(body.length);
        buffer.putShort((short) packetId);
        buffer.putInt((int) sendSeq);
        buffer.putInt((int) random);
        buffer.putShort((short) crc16(result, 0, HEAD_CRC_OFFSET));
        buffer.putShort((short) crc16(body, 0, body.length));
        System.arraycopy(body, 0, result, HEADER_BYTES, body.length);
        return result;
    }

    public static PacketFrame decode(byte[] packet) {
        return decode(packet, DEFAULT_MAX_BODY_BYTES);
    }

    public static PacketFrame decode(byte[] packet, int maxBodyBytes) {
        if (packet == null || packet.length < HEADER_BYTES) {
            throw new IllegalArgumentException("packet is shorter than the XS header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        int bodyLength = buffer.getInt(BODY_LENGTH_OFFSET);
        if (bodyLength < 0 || bodyLength > maxBodyBytes || packet.length != HEADER_BYTES + bodyLength) {
            throw new IllegalArgumentException("packet length mismatch");
        }
        int packetId = Short.toUnsignedInt(buffer.getShort(PACKET_ID_OFFSET));
        long sendSeq = Integer.toUnsignedLong(buffer.getInt(SEND_SEQ_OFFSET));
        long random = Integer.toUnsignedLong(buffer.getInt(RANDOM_OFFSET));
        int expectedHeadCrc = Short.toUnsignedInt(buffer.getShort(HEAD_CRC_OFFSET));
        int actualHeadCrc = crc16(packet, 0, HEAD_CRC_OFFSET);
        if (expectedHeadCrc != actualHeadCrc) {
            throw new IllegalArgumentException("invalid packet header CRC");
        }
        int expectedBodyCrc = Short.toUnsignedInt(buffer.getShort(BODY_CRC_OFFSET));
        int actualBodyCrc = crc16(packet, HEADER_BYTES, bodyLength);
        if (expectedBodyCrc != actualBodyCrc) {
            throw new IllegalArgumentException("invalid packet body CRC");
        }
        return new PacketFrame(packetId, sendSeq, random,
                Arrays.copyOfRange(packet, HEADER_BYTES, packet.length));
    }

    public static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    private static void validate(int packetId, long sendSeq, long random, byte[] body, int maxBodyBytes) {
        if (packetId < 0 || packetId > 0xFFFF) {
            throw new IllegalArgumentException("packetId must fit in uint16");
        }
        if (sendSeq < 0 || sendSeq > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("sendSeq must fit in uint32");
        }
        if (random < 0 || random > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("random must fit in uint32");
        }
        if (body == null || body.length > maxBodyBytes) {
            throw new IllegalArgumentException("body exceeds configured maximum");
        }
    }
}
