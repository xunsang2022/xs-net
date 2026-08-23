package com.game.logic.dbsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.dbsync.LogEventCodec.LogEvent;
import com.game.logic.enums.LogLevel;
import com.game.logic.enums.LogType;
import com.game.protocol.pb.LogRecordMessage;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogEventCodecTest {

    private final LogEventCodec codec = new LogEventCodec(new ObjectMapper());

    @Test
    void parsesProtoPayload() {
        LogRecordMessage msg = LogRecordMessage.newBuilder()
                .setLevel(LogLevel.HIGH.ordinal())
                .setType(LogType.LOGIN.ordinal())
                .setMessage("hello")
                .setPlayerId(42L)
                .build();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payload", Base64.getEncoder().encodeToString(msg.toByteArray()));
        fields.put("createTime", "1700000000000");

        LogEvent event = codec.toEvent(new LogStreamRecord("1-0", fields));

        assertEquals(LogLevel.HIGH, event.level());
        assertEquals(LogType.LOGIN, event.type());
        assertEquals("\"hello\"", event.content());
        assertEquals(42L, event.playerId());
        assertEquals(new Timestamp(1700000000000L), event.createTime());
    }

    @Test
    void fallsBackToJsonPayload() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payload", "{\"level\":\"HIGH\",\"type\":\"LOGIN\",\"content\":\"hello\",\"playerId\":\"42\"}");
        fields.put("createTime", "1700000000000");

        LogEvent event = codec.toEvent(new LogStreamRecord("1-0", fields));

        assertEquals(LogLevel.HIGH, event.level());
        assertEquals(LogType.LOGIN, event.type());
        assertEquals("\"hello\"", event.content());
        assertEquals(42L, event.playerId());
        assertEquals(new Timestamp(1700000000000L), event.createTime());
    }

    @Test
    void protoPayloadWithoutOptionalFieldsUsesDefaults() {
        LogRecordMessage msg = LogRecordMessage.newBuilder().build();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payload", Base64.getEncoder().encodeToString(msg.toByteArray()));
        fields.put("createTime", "1700000000000");

        LogEvent event = codec.toEvent(new LogStreamRecord("1-0", fields));

        assertEquals(LogLevel.MEDIUM, event.level());
        assertEquals(LogType.OTHER, event.type());
        assertEquals("\"\"", event.content());
        assertNull(event.playerId());
        assertEquals(new Timestamp(1700000000000L), event.createTime());
    }
}