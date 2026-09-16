package com.xs.net.transport;

import com.xs.net.message.TransportKind;

import java.util.Set;

public record TransportCapabilities(
        TransportKind kind,
        boolean ordered,
        boolean reliable,
        boolean supportsBackpressure,
        int maxPayloadBytes
) {
    public static Set<TransportKind> allKinds() {
        return Set.of(TransportKind.TCP, TransportKind.UDP, TransportKind.WEBSOCKET);
    }
}
