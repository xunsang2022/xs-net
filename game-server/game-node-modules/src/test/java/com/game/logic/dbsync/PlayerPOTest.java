package com.game.logic.dbsync;

import com.game.logic.actor.player.PlayerPO;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPOTest {

    @Test
    void exposesOnlineAsAnUnambiguousMybatisProperty() {
        MetaClass metaClass = MetaClass.forClass(PlayerPO.class, new DefaultReflectorFactory());

        assertEquals(Integer.class, metaClass.getGetterType("online"));
    }

    @Test
    void tracksDirtyFieldsInOrderAndDeduplicates() {
        PlayerPO player = new PlayerPO();
        player.setProperty(PlayerPO::setName, "alice");
        player.setProperty(PlayerPO::setVersion, 7);
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

        PlayerPO result = player.setProperty(PlayerPO::setOnline, 1)
                .setProperty(PlayerPO::setLastLoginAt, new Timestamp(1700000000000L));

        assertEquals(player, result);
        assertEquals(1, player.getOnline());
        assertEquals(1700000000000L, player.getLastLoginAt().getTime());
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
                .setOnline(1)
                .setLastLoginAt(new Timestamp(1700000000000L))
                .setUpdatedAt(new Timestamp(1700000001000L))
                .setVersion(7);

        PlayerPO restored = new PlayerPO();
        restored.fromHash(player.toHash());

        assertEquals(42L, restored.getId());
        assertEquals("alice", restored.getName());
        assertEquals("alice@example.com", restored.getEmail());
        assertEquals(1, restored.getOnline());
        assertEquals(1700000000000L, restored.getLastLoginAt().getTime());
        assertEquals(1700000001000L, restored.getUpdatedAt().getTime());
        assertEquals(7L, restored.getVersionValue());
    }

    @Test
    void toHashOnlyIncludesRequestedFields() {
        PlayerPO player = new PlayerPO().setId(42L).setName("alice").setOnline(1);

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
        PlayerPO player = new PlayerPO().setName("before").setOnline(1);
        player.setProperty(PlayerPO::setName, "before");
        player.setProperty(PlayerPO::setOnline, true);

        Map<String, String> persisted = player.toHash(player.getDirtyFields());
        player.setName("after");
        player.clearDirtyMatching(persisted);

        assertEquals(List.of("name"), player.getDirtyFields());
    }
}
