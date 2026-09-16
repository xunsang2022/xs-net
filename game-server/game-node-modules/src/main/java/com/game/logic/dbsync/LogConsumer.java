package com.game.logic.dbsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.dbsync.LogEventCodec.LogEvent;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Range;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 日志批消费：从 Redis Streams 阻塞读取日志事件，批量 INSERT 到 PostgreSQL。
 * 使用 XREADGROUP BLOCK 阻塞读避免空轮询；单条坏消息跳过并记录，不阻塞整批。
 */
@Slf4j
public class LogConsumer {

    public static final String LOG_EVENTS_KEY = "log:events";
    public static final String LOG_GROUP = "db-sync";
    public static final String LOG_CONSUMER = "log-consumer";
    public static final String LOG_DEAD_LETTER_KEY = "log:events:dead-letter";

    private static final String INSERT_SQL = "INSERT INTO log_events "
            + "(source_stream_id, level, type, message, player_id, created_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (source_stream_id) DO NOTHING";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbcTemplate;
    private final LogEventCodec codec;
    private final DBSyncConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;

    public LogConsumer(StringRedisTemplate redis, JdbcTemplate jdbcTemplate, DBSyncConfig config) {
        this.redis = redis;
        this.jdbcTemplate = jdbcTemplate;
        this.config = config;
        this.codec = new LogEventCodec(new ObjectMapper());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ensureLogGroup();
        worker = new Thread(this::run, "log-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("LogConsumer started: batchSize={}", config.getBatchSize());
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (worker != null) {
            worker.interrupt();
        }
        log.info("LogConsumer stopped");
    }

    private void run() {
        while (running.get()) {
            try {
                consume();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("Log consume round failed; will retry", e);
                sleepQuietly(1000);
            }
        }
    }

    private void consume() throws InterruptedException {
        List<MapRecord<String, String, String>> records = reclaimPending();
        if (records.isEmpty()) {
            records = redis.<String, String>opsForStream().read(
                Consumer.from(LOG_GROUP, LOG_CONSUMER),
                StreamReadOptions.empty().count(config.getBatchSize()).block(Duration.ofSeconds(5)),
                StreamOffset.create(LOG_EVENTS_KEY, ReadOffset.lastConsumed()));
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        Metrics.counter("log.consumer.batch.records").increment(records.size());

        List<LogStreamRecord> streamRecords = records.stream()
                .map(r -> new LogStreamRecord(r.getId().getValue(), r.getValue()))
                .toList();

        List<Object[]> batch = new ArrayList<>();
        List<String> acked = new ArrayList<>();
        for (LogStreamRecord record : streamRecords) {
            try {
                LogEvent event = codec.toEvent(record);
                batch.add(new Object[]{
                        record.id(),
                        event.level().ordinal(),
                        event.type().ordinal(),
                        event.content(),
                        event.playerId(),
                        event.createTime()
                });
                acked.add(record.id());
            } catch (Exception e) {
                redis.opsForStream().add(LOG_DEAD_LETTER_KEY, Map.of(
                        "sourceId", record.id(),
                        "error", String.valueOf(e.getMessage()),
                        "payload", String.valueOf(record.fields())));
                log.warn("Moved malformed log event to dead letter: id={}, err={}", record.id(), e.getMessage());
                Metrics.counter("log.consumer.dead_letter").increment();
                acked.add(record.id());
            }
        }

        if (!batch.isEmpty()) {
            try {
                int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, batch);
                for (int result : results) {
                    if (result == 0) {
                        Metrics.counter("log.consumer.duplicates").increment();
                    }
                }
            } catch (RuntimeException exception) {
                Metrics.counter("log.consumer.database_failures").increment();
                // Do not ACK here: Redis keeps the entries pending for reclaim.
                throw exception;
            }
        }
        if (!acked.isEmpty()) {
            redis.opsForStream().acknowledge(LOG_EVENTS_KEY, LOG_GROUP, acked.toArray(new String[0]));
            Metrics.counter("log.consumer.ack").increment(acked.size());
        }
    }

    private List<MapRecord<String, String, String>> reclaimPending() {
        PendingMessages pending = redis.opsForStream().pending(
                LOG_EVENTS_KEY,
                Consumer.from(LOG_GROUP, LOG_CONSUMER),
                Range.unbounded(),
                config.getBatchSize());
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<RecordId> reclaimIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(30)) >= 0) {
                reclaimIds.add(message.getId());
            }
        }
        if (reclaimIds.isEmpty()) {
            return List.of();
        }
        return redis.<String, String>opsForStream().claim(
                LOG_EVENTS_KEY,
                LOG_GROUP,
                LOG_CONSUMER,
                Duration.ofSeconds(30),
                reclaimIds.toArray(RecordId[]::new));
    }

    private void ensureLogGroup() {
        try {
            redis.opsForStream().createGroup(LOG_EVENTS_KEY, ReadOffset.from("0"), LOG_GROUP);
        } catch (Exception e) {
            // BUSYGROUP 表示消费组已存在，属正常情况
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private boolean isBusyGroup(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && cur.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
