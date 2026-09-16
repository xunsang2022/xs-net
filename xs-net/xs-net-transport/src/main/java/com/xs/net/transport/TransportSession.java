package com.xs.net.transport;

import com.xs.net.message.TransportKind;
import com.xs.net.message.pb.NetMessage;

public interface TransportSession {
    String sessionId();

    TransportKind kind();

    void write(NetMessage message);

    boolean isActive();

    void close();
}
