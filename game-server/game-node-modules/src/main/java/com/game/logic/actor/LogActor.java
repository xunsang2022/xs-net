package com.game.logic.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.context.LogicContext;
import com.game.logic.log.LogLevel;
import com.game.logic.log.LogType;
import com.game.logic.redis.RedisStore;
import com.game.message.actor.S2C;
import com.game.message.actor.XSSystem;
import com.game.message.rpc.GameRpcEnvelope;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.CS_LOG_REPORT;
import com.game.protocol.pb.LogRecordMessage;
import com.game.protocol.pb.SC_LOG_REPORT;
import com.xs.core.akka.AkkaKit;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log actor that writes JSON log events to Redis {@code log:events} and lets
 * the db-sync service persist them to PostgreSQL.
 */
@Slf4j
public class LogActor extends AbstractBehavior<XSSystem> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisStore redisStore;

    public LogActor(ActorContext<XSSystem> context) {
        super(context);
    }

    public static Behavior<XSSystem> create() {
        return Behaviors.setup(LogActor::new);
    }

    @Override
    public Receive<XSSystem> createReceive() {
        return newReceiveBuilder()
                .onMessage(GameRpcEnvelope.class, this::onRpc)
                .onSignal(PostStop.class, signal -> onPostStop())
                .build();
    }

    private Behavior<XSSystem> onRpc(GameRpcEnvelope envelope) {
        if (envelope.payload() instanceof CS_LOG_REPORT report) {
            return handleClientLogReport(report);
        }
        if (envelope.payload() instanceof LogRecordMessage record) {
            return handleLogRecord(record);
        }
        log.warn("Unhandled rpc payload: {}", envelope.payload().getClass().getName());
        return this;
    }

    private Behavior<XSSystem> handleClientLogReport(CS_LOG_REPORT msg) {
        try {
            if (msg.getLogLevel() < 0 || msg.getLogLevel() >= LogLevel.values().length) {
                log.warn("Invalid log level: {}", msg.getLogLevel());
                sendLogReport(msg.hasSessionId() ? msg.getSessionId() : "", 1, null);
                return this;
            }

            if (msg.getLogType() < 0 || msg.getLogType() >= LogType.values().length) {
                log.warn("Invalid log type: {}", msg.getLogType());
                sendLogReport(msg.hasSessionId() ? msg.getSessionId() : "", 1, null);
                return this;
            }

            long logId = System.nanoTime();
            writeLog(
                    LogLevel.valueOf(msg.getLogLevel()),
                    LogType.valueOf(msg.getLogType()),
                    msg.hasContent() ? msg.getContent() : null,
                    null,
                    logId
            );
            sendLogReport(msg.hasSessionId() ? msg.getSessionId() : "", 0, logId);
        } catch (Exception e) {
            log.error("Error processing log report", e);
            sendLogReport(msg.hasSessionId() ? msg.getSessionId() : "", 1, null);
        }
        return this;
    }

    private void sendLogReport(String sessionId, int result, Long logId) {
        SC_LOG_REPORT.Builder builder = SC_LOG_REPORT.newBuilder().setResult(result);
        if (sessionId != null && !sessionId.isEmpty()) {
            builder.setSessionId(sessionId);
        }
        if (logId != null) {
            builder.setLogId(logId);
        }
        AkkaKit.tell(S2C.class, GameRpcMessages.wrap(builder.build()), sessionId);
    }

    private Behavior<XSSystem> handleLogRecord(LogRecordMessage msg) {
        try {
            writeLog(
                    LogLevel.valueOf(msg.getLevel()),
                    LogType.valueOf(msg.getType()),
                    toJson(msg.hasMessage() ? msg.getMessage() : null),
                    msg.hasPlayerId() ? msg.getPlayerId() : null,
                    System.nanoTime()
            );
        } catch (Exception e) {
            log.error("Error processing internal log record", e);
        }
        return this;
    }

    private void writeLog(LogLevel level, LogType type, String content, Long playerId, long logId) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("id", String.valueOf(logId));
        event.put("level", level.name());
        event.put("type", type.name());
        event.put("content", content);
        event.put("playerId", playerId == null ? "" : String.valueOf(playerId));
        event.put("createTime", String.valueOf(System.currentTimeMillis()));
        redisStore().writeLog(event);
    }

    private Behavior<XSSystem> onPostStop() {
        log.info("LogActor stopping");
        return Behaviors.same();
    }

    private RedisStore redisStore() {
        if (redisStore == null) {
            redisStore = LogicContext.getRedisStore();
        }
        return redisStore;
    }

    private String toJson(String message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Failed to convert message to JSON", e);
            return "\"" + message.replace("\"", "\\\"") + "\"";
        }
    }
}
