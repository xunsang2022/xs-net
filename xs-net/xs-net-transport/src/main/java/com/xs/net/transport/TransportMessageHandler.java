package com.xs.net.transport;

@FunctionalInterface
public interface TransportMessageHandler {
    void onMessage(InboundFrame frame);
}
