package com.xs.net.transport;

import com.xs.net.message.TransportKind;

public record TransportCapabilities(
        TransportKind kind,
        boolean ordered,
        boolean reliable,
        boolean supportsBackpressure,
        int maxPayloadBytes
) {
}
