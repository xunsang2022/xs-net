package com.game.logic.context;

import com.game.logic.redis.RedisStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LogicContextTest {

    @Test
    void redisStoreCanBeRegisteredAndRead() {
        RedisStore store = mock(RedisStore.class);
        LogicContext.setRedisStore(store);
        assertSame(store, LogicContext.getRedisStore());
        LogicContext.setRedisStore(null);
    }

    @Test
    void redisStoreThrowsBeforeInitialization() {
        LogicContext.setRedisStore(null);
        assertThrows(IllegalStateException.class, LogicContext::getRedisStore);
    }

    @Test
    void syncIntervalDefaultsAndClampsToPositive() {
        assertEquals(1000L, LogicContext.getSyncIntervalMs());
        LogicContext.setSyncIntervalMs(250L);
        assertEquals(250L, LogicContext.getSyncIntervalMs());
        LogicContext.setSyncIntervalMs(0L);
        assertEquals(1L, LogicContext.getSyncIntervalMs());
        LogicContext.setSyncIntervalMs(1000L);
    }
}
