package com.game.dbsync;

import com.game.po.log.LogEntity;
import com.game.po.log.LogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class LogEventConsumer {
    private final DbSyncStore store;
    private final LogRepository logRepository;
    private final LogEventCodec codec;
    private final DbSyncProperties properties;
    private final DbSyncMetrics metrics;

    public LogEventConsumer(
            DbSyncStore store,
            LogRepository logRepository,
            LogEventCodec codec,
            DbSyncProperties properties,
            DbSyncMetrics metrics
    ) {
        this.store = store;
        this.logRepository = logRepository;
        this.codec = codec;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${game.db.log-poll-ms:1000}")
    public void consume() {
        try {
            List<LogStreamRecord> records = store.readLogEvents(Math.max(1, properties.getLogBatchSize()));
            if (records.isEmpty()) {
                metrics.updateLogBacklog(store.logBacklog());
                return;
            }
            List<LogEntity> entities = codec.toEntities(records);
            logRepository.saveAll(entities);
            store.ackLogEvents(records.stream().map(LogStreamRecord::id).toList());
            metrics.recordLogsFlushed(entities.size());
            metrics.updateLogBacklog(store.logBacklog());
        } catch (Exception e) {
            log.error("Failed to flush log events; batch will be retried", e);
        }
    }
}
