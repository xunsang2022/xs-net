package com.xs.net.transport;

import com.xs.net.message.TransportKind;

import java.util.Arrays;

public final class RawFrameCodec implements FrameCodec {
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024;
    private final TransportKind kind;
    private final int maxPayloadBytes;

    public RawFrameCodec(TransportKind kind) {
        this(kind, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    public RawFrameCodec(TransportKind kind, int maxPayloadBytes) {
        if (kind == TransportKind.TCP) {
            throw new IllegalArgumentException("TCP requires TcpFrameCodec");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.kind = kind;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public TransportKind kind() {
        return kind;
    }

    @Override
    public byte[] encode(byte[] payload) {
        checkSize(payload);
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] decode(byte[] frame) {
        checkSize(frame);
        return Arrays.copyOf(frame, frame.length);
    }

    private void checkSize(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.length > maxPayloadBytes) {
            throw new IllegalArgumentException("Payload exceeds configured maximum: " + payload.length);
        }
    }
}
