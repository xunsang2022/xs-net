package com.game.dbsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.enums.LogLevel;
import com.game.enums.LogType;
import com.game.po.log.LogEntity;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LogEventCodec {
    private final ObjectMapper objectMapper;

    public LogEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<LogEntity> toEntities(List<LogStreamRecord> records) {
        List<LogEntity> entities = new ArrayList<>(records.size());
        for (LogStreamRecord record : records) {
            entities.add(toEntity(record));
        }
        return entities;
    }

    public LogEntity toEntity(LogStreamRecord record) {
        try {
            Map<String, String> fields = record.fields();
            JsonNode payload = objectMapper.readTree(fields.getOrDefault("payload", "{}"));
            LogEntity entity = new LogEntity();
            entity.setLogLevel(parseLevel(payload));
            entity.setLogType(parseType(payload));
            entity.setContent(asJson(payload.path("content").asText("")));
            entity.setPlayerId(parsePlayerId(payload));
            entity.setCreateTime(parseCreateTime(payload, fields));
            return entity;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid log event: " + record.id(), e);
        }
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
}
