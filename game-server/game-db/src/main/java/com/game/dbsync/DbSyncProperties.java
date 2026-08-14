package com.game.dbsync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "game.db")
public class DbSyncProperties {
    private int bgsaveSeconds = 3;
    private int logBatchSize = 100;
    private long logPollMs = 1000;
    private int flushConcurrency = 4;
    private final Backfill backfill = new Backfill();

    @Data
    public static class Backfill {
        private boolean enabled = false;
    }
}
