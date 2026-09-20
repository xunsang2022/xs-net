package com.xs.net.transport;

import java.util.Arrays;
import java.util.Objects;

/** A decoded XS packet. */
public record PacketFrame(int packetId, long sendSeq, long random, byte[] body) {
    public PacketFrame {
        if (packetId < 0 || packetId > 0xFFFF) {
            throw new IllegalArgumentException("packetId must fit in uint16");
        }
        if (sendSeq < 0 || sendSeq > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("sendSeq must fit in uint32");
        }
        if (random < 0 || random > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("random must fit in uint32");
        }
        Objects.requireNonNull(body, "body");
        body = Arrays.copyOf(body, body.length);
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }
}
