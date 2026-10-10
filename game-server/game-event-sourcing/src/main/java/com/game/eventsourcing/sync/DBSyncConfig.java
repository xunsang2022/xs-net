package com.game.eventsourcing.sync;

import lombok.Data;

/**
 * 进程内批写器配置。
 */
@Data
public class DBSyncConfig {
    /** 定时 flush 间隔（秒） */
    private int bgsaveSeconds = 3;
    /** 每批最大记录数 */
    private int batchSize = 100;
    /** flush 并发分片数 */
    private int flushConcurrency = 4;
    /** 脏记录 map 上限，超限拒绝新提交，防止失败持续时内存无限增长 */
    private int maxDirtyRecords = 100000;
    /** 脏记录估算内存上限（字节）。 */
    private long maxDirtyBytes = 128L * 1024L * 1024L;
    /** 单条记录最大重试次数，超过后继续保留但升级告警。 */
    private int maxRetryAttempts = 12;
    /** 首次失败重试退避时间（毫秒）。 */
    private long retryBackoffMs = 250L;
    /** 日志轮询间隔（毫秒） */
    private long logPollMs = 1000;

    /** 单个分片 flush 的超时（毫秒），超时则取消并走重试 */
    private long flushTimeoutMs = 30_000L;
    /** stop() 等待线程池终止的超时（毫秒） */
    private long shutdownTimeoutMs = 10_000L;
    /** 指数退避单次延迟上限（毫秒） */
    private long maxRetryBackoffMs = 60_000L;
    /** 终态失败记录的保留期（毫秒），超过后回收 buffer 容量 */
    private long quarantineRetentionMs = 30 * 60_000L; // 30 分钟
}