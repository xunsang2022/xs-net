package com.game.dbsync;

import tools.jackson.databind.ObjectMapper;
import com.game.enums.LogLevel;
import com.game.enums.LogType;
import com.game.po.log.LogEntity;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogEventCodecTest {
    private final LogEventCodec codec = new LogEventCodec(new ObjectMapper());

    @Test
    void mapsStreamPayloadToLogEntity() {
        String payload = "{"
                + "\"id\":\"1\","
                + "\"level\":\"MEDIUM\","
                + "\"type\":\"LOGIN\","
                + "\"content\":\"\\\"Player online\\\"\","
                + "\"playerId\":\"42\","
                + "\"createTime\":\"123\""
                + "}";
        LogStreamRecord record = new LogStreamRecord("1-0", Map.of("payload", payload, "createTime", "123"));

        LogEntity entity = codec.toEntity(record);

        assertEquals(LogLevel.MEDIUM, entity.getLogLevel());
        assertEquals(LogType.LOGIN, entity.getLogType());
        assertEquals(Long.valueOf(42L), entity.getPlayerId());
        assertEquals(new Timestamp(123L), entity.getCreateTime());
    }

    @Test
    void wrapsPlainContentAsJson() {
        String payload = "{"
                + "\"level\":\"LOW\","
                + "\"type\":\"SYSTEM\","
                + "\"content\":\"plain text\""
                + "}";

        LogEntity entity = codec.toEntity(new LogStreamRecord("2-0", Map.of("payload", payload)));

        assertEquals("plain text", entity.getContent().substring(1, entity.getContent().length() - 1));
    }
}
