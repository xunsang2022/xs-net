package com.game.logic.dbsync;

import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPOTest {

    @Test
    void mapsDirtyRecordToActiveRecordWithOptimisticLockVersions() {
        DirtyRecord record = new DirtyRecord(
                42L,
                Map.of("name", "alice", "version", "7", "online", "1"),
                6L,
                7L);

        PlayerPO player = PlayerPO.fromDirtyRecord(record);

        assertEquals(42L, player.getId());
        assertEquals("alice", player.getName());
        assertEquals(1, player.getOnline());
        assertEquals(6, player.getExpectedVersion());
        assertEquals(7, player.getVersion());
    }

    @Test
    void exposesOnlineAsAnUnambiguousMybatisProperty() {
        MetaClass metaClass = MetaClass.forClass(PlayerPO.class, new DefaultReflectorFactory());

        assertEquals(Integer.class, metaClass.getGetterType("online"));
    }

    @Test
    void tracksDirtyFieldsInOrderAndDeduplicates() {
        PlayerPO player = new PlayerPO();
        player.setProperty(PlayerPO::setName, "alice");
        player.setProperty(PlayerPO::setVersionValue, 7L);
        player.setProperty(PlayerPO::setName, "bob");

        assertEquals(List.of("name", "version"), player.getDirtyFields());
        assertTrue(player.hasDirtyFields());

        player.clearDirty();
        assertFalse(player.hasDirtyFields());
        assertTrue(player.getDirtyFields().isEmpty());
    }

    @Test
    void rejectsUnknownDirtyField() {
        PlayerPO player = new PlayerPO();
        assertThrows(IllegalArgumentException.class, () -> player.markDirty("unknown"));
    }

    @Test
    void setPropertyIsFluentAndInvokesCompatibleSetter() {
        PlayerPO player = new PlayerPO();

        PlayerPO result = player.setProperty(PlayerPO::setOnline, true)
                .setProperty(PlayerPO::setLastLoginAtMillis, 1700000000000L);

        assertEquals(player, result);
        assertTrue(player.isOnlineState());
        assertEquals(1700000000000L, player.getLastLoginAtMillis());
        assertEquals(List.of("online", "last_login_at"), player.getDirtyFields());
    }

    @Test
    void setPropertyRejectsSetterFromNonModelClass() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerPO().setProperty(PlayerPOTest::nonModelSetter, "value"));
    }

    private static void nonModelSetter(PlayerPO player, String value) {
        throw new AssertionError("non-model setter should not be invoked");
    }

    @Test
    void hashRoundTripPreservesRuntimeFields() {
        PlayerPO player = new PlayerPO()
                .setId(42L)
                .setName("alice")
                .setEmail("alice@example.com")
                .setOnline(true)
                .setLastLoginAtMillis(1700000000000L)
                .setUpdatedAtMillis(1700000001000L)
                .setVersionValue(7L);

        PlayerPO restored = new PlayerPO();
        restored.fromHash(player.toHash());

        assertEquals(42L, restored.getId());
        assertEquals("alice", restored.getName());
        assertEquals("alice@example.com", restored.getEmail());
        assertTrue(restored.isOnlineState());
        assertEquals(1700000000000L, restored.getLastLoginAtMillis());
        assertEquals(1700000001000L, restored.getUpdatedAtMillis());
        assertEquals(7L, restored.getVersionValue());
    }

    @Test
    void toHashOnlyIncludesRequestedFields() {
        PlayerPO player = new PlayerPO().setId(42L).setName("alice").setOnline(true);

        Map<String, String> partial = player.toHash(List.of("name", "online"));

        assertEquals(2, partial.size());
        assertEquals("alice", partial.get("name"));
        assertEquals("1", partial.get("online"));
        assertNull(partial.get("id"));
    }

    @Test
    void fieldValueRejectsUnknownField() {
        PlayerPO player = new PlayerPO();
        assertThrows(IllegalArgumentException.class, () -> player.fieldValue("unknown"));
    }

    @Test
    void clearDirtyMatchingKeepsFieldsChangedWhileIoWasInFlight() {
        PlayerPO player = new PlayerPO().setName("before").setOnline(true);
        player.setProperty(PlayerPO::setName, "before");
        player.setProperty(PlayerPO::setOnline, true);

        Map<String, String> persisted = player.toHash(player.getDirtyFields());
        player.setName("after");
        player.clearDirtyMatching(persisted);

        assertEquals(List.of("name"), player.getDirtyFields());
    }
}
