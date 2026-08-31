package com.game.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class WebSocketConfig {

    private final int port;
    private final String path;
    private final int maxFrameLength;

    public WebSocketConfig(
            @Value("${game.websocket.port:8083}") int port,
            @Value("${game.websocket.path:/game}") String path,
            @Value("${game.websocket.max-frame-length:65536}") int maxFrameLength) {
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.maxFrameLength = maxFrameLength;
    }

    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }
}
