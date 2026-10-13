package com.game.eventsourcing.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.protocol.pb.LogRecordCommand;

import java.sql.Timestamp;
import java.util.Base64;
import java.util.Map;

/**
 * 将 Redis Streams 中的日志事件解析为可落库的字段。
 */
public class LogEventCodec {

    private final ObjectMapper objectMapper;

    public LogEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LogEvent toEvent(LogStreamRecord record) {
        try {
            Map<String, String> fields = record.fields();
            String payload = fields.getOrDefault("payload", "");
            if (payload.startsWith("{")) {
                // 旧格式：JSON payload（兼容存量数据）
                return fromJson(payload, fields);
            }
            return fromProto(payload, fields);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid log event: " + record.id(), e);
        }
    }

    private LogEvent fromProto(String payload, Map<String, String> fields) throws Exception {
        LogRecordCommand msg = LogRecordCommand.parseFrom(Base64.getDecoder().decode(payload));
        LogLevel level = msg.hasLevel() ? LogLevel.valueOf(msg.getLevel()) : LogLevel.MEDIUM;
        LogType type = msg.hasType() ? LogType.valueOf(msg.getType()) : LogType.OTHER;
        String content = asJson(msg.hasMessage() ? msg.getMessage() : "");
        Long playerId = msg.hasPlayerId() ? msg.getPlayerId() : null;
        Timestamp createTime = parseCreateTime(fields);
        return new LogEvent(level, type, content, playerId, createTime);
    }

    private LogEvent fromJson(String payload, Map<String, String> fields) throws Exception {
        JsonNode json = objectMapper.readTree(payload);
        LogLevel level = parseLevel(json);
        LogType type = parseType(json);
        String content = asJson(json.path("content").asText(""));
        Long playerId = parsePlayerId(json);
        Timestamp createTime = parseCreateTime(json, fields);
        return new LogEvent(level, type, content, playerId, createTime);
    }

    private LogLevel parseLevel(JsonNode payload) {
        String text = payload.path("level").asText("");
        if (!text.isBlank()) {
            try {
                return LogLevel.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                // fall through to ordinal
            }
        }
        return LogLevel.valueOf(payload.path("level").asInt(LogLevel.MEDIUM.ordinal()));
    }

    private LogType parseType(JsonNode payload) {
        String text = payload.path("type").asText("");
        if (!text.isBlank()) {
            try {
                return LogType.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                // fall through to ordinal
            }
        }
        return LogType.valueOf(payload.path("type").asInt(LogType.OTHER.ordinal()));
    }

    private String asJson(String content) throws Exception {
        if (content == null || content.isBlank()) {
            return objectMapper.writeValueAsString("");
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            return trimmed;
        }
        return objectMapper.writeValueAsString(content);
    }

    private Long parsePlayerId(JsonNode payload) {
        JsonNode playerId = payload.path("playerId");
        if (playerId.isMissingNode() || playerId.isNull() || playerId.asText("").isBlank()) {
            return null;
        }
        if (playerId.isNumber()) {
            return playerId.asLong();
        }
        try {
            return Long.valueOf(playerId.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Timestamp parseCreateTime(JsonNode payload, Map<String, String> streamFields) {
        long millis = payload.path("createTime").asLong(0L);
        if (millis <= 0L) {
            try {
                millis = Long.parseLong(streamFields.getOrDefault("createTime", "0"));
            } catch (NumberFormatException ignored) {
                millis = 0L;
            }
        }
        return millis > 0L ? new Timestamp(millis) : new Timestamp(System.currentTimeMillis());
    }

    private Timestamp parseCreateTime(Map<String, String> streamFields) {
        long millis = 0L;
        try {
            millis = Long.parseLong(streamFields.getOrDefault("createTime", "0"));
        } catch (NumberFormatException ignored) {
            millis = 0L;
        }
        return millis > 0L ? new Timestamp(millis) : new Timestamp(System.currentTimeMillis());
    }

    /**
     * 解析后的日志事件（待落库）。
     */
    public record LogEvent(LogLevel level, LogType type, String content, Long playerId, Timestamp createTime) {
    }
}
