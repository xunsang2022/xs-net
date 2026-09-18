package com.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameLogicApplicationConfigTest {

    @Test
    void databaseAndRedisSettingsAreReadFromApplicationYaml() {
        String previousHost = System.getProperty("game.redis.host");
        System.setProperty("game.redis.host", "system-property-host");
        try {
            assertEquals("172.21.255.41", GameLogicApplication.fileConfig("game.redis.host", "fallback"));
        } finally {
            if (previousHost == null) {
                System.clearProperty("game.redis.host");
            } else {
                System.setProperty("game.redis.host", previousHost);
            }
        }
    }
}
