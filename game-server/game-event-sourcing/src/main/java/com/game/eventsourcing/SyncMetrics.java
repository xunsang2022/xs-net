package com.game.eventsourcing;

/**
 * 同步引擎指标上报接口。
 */
public interface SyncMetrics {
    /**
     * 上报一次 flush 耗时（纳秒）。
     */
    void recordFlush(long durationNanos);

    /**
     * 上报一次 flush 分片失败。
     */
    void recordFlushFailure();

    /**
     * 上报重试次数耗尽。
     */
    void recordRetryExhausted();

    /**
     * 获取当前待同步的脏记录数。
     */
    int dirtySize();

    /**
     * 获取当前待同步的脏记录估算字节数。
     */
    long dirtyBytes();
}
