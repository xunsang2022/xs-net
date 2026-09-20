package com.xs.net.transport;

/** Packet identifiers shared by all non-KCP application transports. */
public final class PacketId {
    public static final int DATA = 1;
    public static final int ACK = 2;
    public static final int HELLO = 3;
    public static final int HEARTBEAT = 4;
    public static final int ERROR = 5;

    private PacketId() {
    }
}
