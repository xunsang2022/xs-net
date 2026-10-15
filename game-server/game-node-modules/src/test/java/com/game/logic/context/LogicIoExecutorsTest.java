package com.game.logic.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicIoExecutorsTest {

    @Test
    void createsSeparatelyNamedBlockingIoPools() throws Exception {
        try (LogicIoExecutors executors = LogicIoExecutors.fixed(1, 1, 16)) {
            String redisThread = executors.redisIoExecutor().submit(() -> Thread.currentThread().getName())
                    .get(1, TimeUnit.SECONDS);
            String dbThread = executors.dbIoExecutor().submit(() -> Thread.currentThread().getName())
                    .get(1, TimeUnit.SECONDS);

            assertTrue(redisThread.startsWith("redis-io-executor-"));
            assertTrue(dbThread.startsWith("db-io-executor-"));
        }
    }

    @Test
    void rejectsWhenPoolAndQueueAreSaturated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        LogicIoExecutors executors = LogicIoExecutors.fixed(1, 1, 1);
        try {
            // 占满唯一工作线程 + 1 个队列位，第三个提交应触发 AbortPolicy 拒收
            executors.dbIoExecutor().submit(() -> release.await(5, TimeUnit.SECONDS));
            executors.dbIoExecutor().submit(() -> release.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class,
                    () -> executors.dbIoExecutor().submit(() -> release.await(5, TimeUnit.SECONDS)));
        } finally {
            release.countDown();
            executors.close();
        }
    }
}
