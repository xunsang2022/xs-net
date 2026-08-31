package com.game.gateway.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebSocketConfigTest {

    @Test
    void normalizesTheWebSocketPath() {
        WebSocketConfig config = new WebSocketConfig(8083, "game", 65536);

        assertEquals(8083, config.getPort());
        assertEquals("/game", config.getPath());
        assertEquals(65536, config.getMaxFrameLength());
    }
}
