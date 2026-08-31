package com.game.logic.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public final class LogicRuntimeConfiguration implements AutoCloseable {

    public static final String CONFIG_PATH_PROPERTY = "game.config.path";

    private final Path path;
    private final Consumer<Map<String, String>> consumer;
    private final ScheduledExecutorService scheduler;
    private volatile FileTime lastModified;
    private volatile boolean configurationAvailable = true;

    public LogicRuntimeConfiguration(Path path, Consumer<Map<String, String>> consumer) {
        this.path = path;
        this.consumer = consumer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "logic-config-reloader");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static LogicRuntimeConfiguration fromSystemProperty(Consumer<Map<String, String>> consumer) {
        String configuredPath = System.getProperty(CONFIG_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        return new LogicRuntimeConfiguration(Path.of(configuredPath), consumer);
    }

    public void start(long pollIntervalMs) {
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        }
        reloadIfChanged();
        scheduler.scheduleWithFixedDelay(this::reloadIfChanged,
                pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void reload() {
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(input);
            Map<String, String> settings = new LinkedHashMap<>();
            if (loaded instanceof Map<?, ?> root) {
                flatten("", root, settings);
            }
            consumer.accept(Map.copyOf(settings));
            lastModified = Files.getLastModifiedTime(path);
            markConfigurationAvailable();
            log.info("Reloaded runtime configuration from {}", path);
        } catch (Exception exception) {
            markConfigurationUnavailable("Failed to reload runtime configuration from {}", exception);
        }
    }

    private void reloadIfChanged() {
        try {
            FileTime currentLastModified = Files.getLastModifiedTime(path);
            if (lastModified == null || currentLastModified.compareTo(lastModified) > 0) {
                reload();
            }
        } catch (Exception exception) {
            markConfigurationUnavailable("Unable to inspect runtime configuration {}", exception);
        }
    }

    private void markConfigurationUnavailable(String message, Exception exception) {
        if (configurationAvailable) {
            configurationAvailable = false;
            log.warn(message, path, exception);
        }
    }

    private void markConfigurationAvailable() {
        if (!configurationAvailable) {
            log.info("Runtime configuration became available again: {}", path);
        }
        configurationAvailable = true;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<?, ?> source, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else if (value != null) {
                target.put(key, String.valueOf(value));
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
