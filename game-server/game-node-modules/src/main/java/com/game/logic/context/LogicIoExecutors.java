package com.game.logic.context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared process-level pools for blocking external I/O. */
public final class LogicIoExecutors implements AutoCloseable {

    private final ExecutorService redisIoExecutor;
    private final ExecutorService dbIoExecutor;

    private LogicIoExecutors(ExecutorService redisIoExecutor, ExecutorService dbIoExecutor) {
        this.redisIoExecutor = redisIoExecutor;
        this.dbIoExecutor = dbIoExecutor;
    }

    public static LogicIoExecutors fixed(int redisPoolSize, int dbPoolSize, int queueCapacity) {
        return new LogicIoExecutors(
                newExecutor("redis-io-executor", redisPoolSize, queueCapacity),
                newExecutor("db-io-executor", dbPoolSize, queueCapacity));
    }

    public ExecutorService redisIoExecutor() {
        return redisIoExecutor;
    }

    public ExecutorService dbIoExecutor() {
        return dbIoExecutor;
    }

    @Override
    public void close() {
        shutdown(redisIoExecutor);
        shutdown(dbIoExecutor);
    }

    private static ExecutorService newExecutor(String name, int poolSize, int queueCapacity) {
        if (poolSize < 1) {
            throw new IllegalArgumentException(name + " pool size must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(name + " queue capacity must be positive");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
