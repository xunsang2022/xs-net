package com.game.gateway.netty;

import io.netty.channel.Channel;
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
        Channel channel = mock(Channel.class);
        ChannelRegistry.INSTANCE.register("ch-1", channel);

        assertTrue(ChannelRegistry.INSTANCE.isRegistered("ch-1"));
        assertSame(channel, ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(1, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void removeReturnsChannelAndClears() {
        Channel channel = mock(Channel.class);
        ChannelRegistry.INSTANCE.register("ch-1", channel);

        assertSame(channel, ChannelRegistry.INSTANCE.remove("ch-1"));
        assertFalse(ChannelRegistry.INSTANCE.isRegistered("ch-1"));
        assertNull(ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(0, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void registerOverwritesExisting() {
        Channel first = mock(Channel.class);
        Channel second = mock(Channel.class);
        ChannelRegistry.INSTANCE.register("ch-1", first);
        ChannelRegistry.INSTANCE.register("ch-1", second);

        assertSame(second, ChannelRegistry.INSTANCE.get("ch-1"));
        assertEquals(1, ChannelRegistry.INSTANCE.size());
    }

    @Test
    void blankChannelIdIsIgnored() {
        Channel channel = mock(Channel.class);
        ChannelRegistry.INSTANCE.register("", channel);
        ChannelRegistry.INSTANCE.register(null, channel);

        assertEquals(0, ChannelRegistry.INSTANCE.size());
    }
}