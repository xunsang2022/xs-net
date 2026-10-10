package com.game.eventsourcing.sync;

/**
 * 同步引擎指标回调。实现方接入具体监控系统（Micrometer / Prometheus 等）。
 * 所有方法都应是非阻塞、无异常的；引擎不处理指标实现抛出的异常。
 */
public interface SyncMetrics {
    /** 一轮 flush 完成（含耗时，纳秒） */
    void recordFlush(long elapsedNanos);
    /** 一次 flush 分片/批次失败 */
    void recordFlushFailure();
    /** 一条记录重试次数耗尽，进入终态 */
    void recordRetryExhausted();
    /** 一条终态记录被保留期回收 */
    void recordQuarantineReclaimed();
    /** submit 因容量被拒绝（P1 新增） */
    void recordSubmitRejected();
    /** 上报当前待同步脏记录量与估算字节数（flush 触发时由引擎调用，实现方按 engine tag 区分引擎） */
    void updateDirty(int size, long bytes);
}