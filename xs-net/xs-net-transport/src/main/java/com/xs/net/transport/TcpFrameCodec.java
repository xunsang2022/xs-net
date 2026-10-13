package com.xs.net.transport;

import com.xs.net.message.TransportKind;

public final class TcpFrameCodec implements FrameCodec {
    public static final int HEADER_BYTES = PacketCodec.HEADER_BYTES;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024;

    private final int maxPayloadBytes;

    public TcpFrameCodec() {
        this(DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public TcpFrameCodec(int maxPayloadBytes) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public TransportKind kind() {
        return TransportKind.TCP;
    }

    @Override
    public byte[] encode(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        checkSize(payload.length);
        return PacketCodec.encode(PacketId.DATA, 0, 0, payload, maxPayloadBytes);
    }

    @Override
    public byte[] decode(byte[] frame) {
        return PacketCodec.decode(frame, maxPayloadBytes).body();
    }

    private void checkSize(int length) {
        if (length > maxPayloadBytes) {
            throw new IllegalArgumentException("TCP payload exceeds configured maximum: " + length);
        }
    }
}
