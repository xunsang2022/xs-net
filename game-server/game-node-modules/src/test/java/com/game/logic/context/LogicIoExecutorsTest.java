package com.game.logic.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicIoExecutorsTest {

    @Test
    void createsSeparatelyNamedBlockingIoPools() throws Exception {
        try (LogicIoExecutors executors = LogicIoExecutors.fixed(1, 1)) {
            String redisThread = executors.redisIoExecutor().submit(() -> Thread.currentThread().getName())
                    .get(1, TimeUnit.SECONDS);
            String dbThread = executors.dbIoExecutor().submit(() -> Thread.currentThread().getName())
                    .get(1, TimeUnit.SECONDS);

            assertTrue(redisThread.startsWith("redis-io-executor-"));
            assertTrue(dbThread.startsWith("db-io-executor-"));
        }
    }
}
