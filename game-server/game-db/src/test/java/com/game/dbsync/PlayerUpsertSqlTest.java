package com.game.dbsync;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerUpsertSqlTest {

    @Test
    void generatesConflictUpsertSql() {
        assertTrue(PlayerUpsertSql.SQL.startsWith("INSERT INTO users"));
        assertTrue(PlayerUpsertSql.SQL.contains("ON CONFLICT (id) DO UPDATE"));
        assertTrue(PlayerUpsertSql.SQL.contains("last_login_at = COALESCE(EXCLUDED.last_login_at, users.last_login_at)"));
    }

    @Test
    void mapsHashFieldsToPreparedStatementParams() {
        Map<String, String> fields = Map.of(
                "id", "42",
                "name", "alice",
                "email", "alice@example.com",
                "online", "1",
                "last_login_at", "1000",
                "updated_at", "2000",
                "version", "3");

        Object[] params = PlayerUpsertSql.params(42L, fields);

        assertEquals(7, params.length);
        assertEquals(42L, params[0]);
        assertEquals("alice", params[1]);
        assertEquals("alice@example.com", params[2]);
        assertEquals(1, params[3]);
        assertEquals(new Timestamp(1000L), params[4]);
        assertEquals(new Timestamp(2000L), params[5]);
        assertEquals(3L, params[6]);
    }

    @Test
    void missingFieldsUseSafeDefaults() {
        Object[] params = PlayerUpsertSql.params(5L, Map.of("id", "5", "name", "bob"));

        assertEquals(5L, params[0]);
        assertEquals("bob", params[1]);
        assertEquals("", params[2]);
        assertEquals(0, params[3]);
        assertEquals(null, params[4]);
        assertEquals(null, params[5]);
        assertEquals(1L, params[6]);
    }

    @Test
    void buildsBatchParams() {
        List<Object[]> batch = PlayerUpsertSql.batchParams(List.of(
                Map.of("id", "1", "name", "a"),
                Map.of("id", "2", "name", "b")));

        assertEquals(2, batch.size());
        assertEquals(1L, batch.get(0)[0]);
        assertEquals(2L, batch.get(1)[0]);
    }
}
