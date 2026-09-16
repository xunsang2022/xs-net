package com.xs.net.transport;

import com.xs.net.message.RequestContext;
import com.xs.net.message.TransportKind;

import java.util.Arrays;
import java.util.Objects;

public record InboundFrame(
        TransportKind kind,
        String sessionId,
        byte[] payload,
        RequestContext context
) {
    public InboundFrame {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(payload, "payload");
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
