package com.xs.net.transport;

import com.xs.net.message.TransportKind;

import java.nio.ByteBuffer;

public final class TcpFrameCodec implements FrameCodec {
    public static final int HEADER_BYTES = Integer.BYTES;
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
        return ByteBuffer.allocate(HEADER_BYTES + payload.length)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    @Override
    public byte[] decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES) {
            throw new IllegalArgumentException("TCP frame is shorter than its length prefix");
        }
        int length = ByteBuffer.wrap(frame, 0, HEADER_BYTES).getInt();
        if (length < 0 || length != frame.length - HEADER_BYTES) {
            throw new IllegalArgumentException("TCP frame length mismatch");
        }
        checkSize(length);
        byte[] payload = new byte[length];
        System.arraycopy(frame, HEADER_BYTES, payload, 0, length);
        return payload;
    }

    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    private void checkSize(int length) {
        if (length > maxPayloadBytes) {
            throw new IllegalArgumentException("TCP payload exceeds configured maximum: " + length);
        }
    }
}
