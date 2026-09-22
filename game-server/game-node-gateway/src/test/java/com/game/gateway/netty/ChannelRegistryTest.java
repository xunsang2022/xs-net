package com.game.gateway.netty;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChannelRegistryTest {

    @AfterEach
    void tearDown() {
        // 清理单例状态，避免测试间相互影响
        ChannelRegistry.INSTANCE.remove("ch-1");
        ChannelRegistry.INSTANCE.remove("ch-2");
    }

    @Test
    void registerAndGet() {
        ClientEndpoint endpoint = mock(ClientEndpoint.class);
        ChannelRegistry.INSTANCE.register("ch-1", endpoint);

        assertTrue(ChannelRegistry.INSTANCE.isRegistered("ch-1"));
        assertSame(endpoint, ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(1, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void removeReturnsEndpointAndClears() {
        ClientEndpoint endpoint = mock(ClientEndpoint.class);
        ChannelRegistry.INSTANCE.register("ch-1", endpoint);

        assertSame(endpoint, ChannelRegistry.INSTANCE.remove("ch-1"));
        assertFalse(ChannelRegistry.INSTANCE.isRegistered("ch-1"));
        assertNull(ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(0, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void registerOverwritesExisting() {
        ClientEndpoint first = mock(ClientEndpoint.class);
        ClientEndpoint second = mock(ClientEndpoint.class);
        ChannelRegistry.INSTANCE.register("ch-1", first);
        ChannelRegistry.INSTANCE.register("ch-1", second);

        assertSame(second, ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(1, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void blankChannelIdIsIgnored() {
        ClientEndpoint endpoint = mock(ClientEndpoint.class);
        ChannelRegistry.INSTANCE.register("", endpoint);
        ChannelRegistry.INSTANCE.register(null, endpoint);

        assertEquals(0, ChannelRegistry.INSTANCE.size());
    }
}
