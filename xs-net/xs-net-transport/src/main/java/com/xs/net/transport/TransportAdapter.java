package com.xs.net.transport;

import java.util.concurrent.CompletableFuture;

public interface TransportAdapter {
    TransportCapabilities capabilities();

    CompletableFuture<Void> start(TransportMessageHandler handler);

    CompletableFuture<Void> stop();
}
