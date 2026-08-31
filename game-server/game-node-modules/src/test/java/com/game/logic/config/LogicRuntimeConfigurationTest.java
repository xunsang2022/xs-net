package com.game.logic.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogicRuntimeConfigurationTest {

    @Test
    void flattensAndPublishesExternalYaml() throws Exception {
        Path configFile = Files.createTempFile("logic-runtime-config", ".yml");
        Files.writeString(configFile, "game:\n  storage:\n    sync-interval-ms: 250\n  logging:\n    level: DEBUG\n");
        AtomicReference<Map<String, String>> received = new AtomicReference<>();

        try (LogicRuntimeConfiguration configuration = new LogicRuntimeConfiguration(configFile, received::set)) {
            configuration.reload();
        } finally {
            Files.deleteIfExists(configFile);
        }

        assertEquals("250", received.get().get("game.storage.sync-interval-ms"));
        assertEquals("DEBUG", received.get().get("game.logging.level"));
    }

    @Test
    void rejectsANonPositivePollingInterval() throws Exception {
        Path configFile = Files.createTempFile("logic-runtime-config", ".yml");
        try (LogicRuntimeConfiguration configuration = new LogicRuntimeConfiguration(configFile, settings -> { })) {
            assertThrows(IllegalArgumentException.class, () -> configuration.start(0));
        } finally {
            Files.deleteIfExists(configFile);
        }
    }
}
