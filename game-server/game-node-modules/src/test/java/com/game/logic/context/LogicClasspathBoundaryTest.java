package com.game.logic.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LogicClasspathBoundaryTest {

    @Test
    void postgresDriverIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.postgresql.Driver"));
    }

    @Test
    void jakartaPersistenceIsNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("jakarta.persistence.Entity"));
    }

    @Test
    void gameDbClassesAreNotOnClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.game.po.player.user.User"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.game.dbsync.DbSyncActor"));
    }
}
