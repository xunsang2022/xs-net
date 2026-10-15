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
}
