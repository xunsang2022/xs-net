package com.game.logic.dbsync;

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
    /** 日志轮询间隔（毫秒） */
    private long logPollMs = 1000;
}
