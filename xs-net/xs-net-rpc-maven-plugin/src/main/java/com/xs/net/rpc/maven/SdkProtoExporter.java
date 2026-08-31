package com.xs.net.rpc.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SdkProtoExporter {

    private SdkProtoExporter() {
    }

    static List<Path> copyProtoFiles(List<String> sourceDirectories, Path targetDirectory) throws IOException {
        if (sourceDirectories == null || sourceDirectories.isEmpty()) {
            throw new IOException("Configure at least one proto source directory");
        }
        clearFiles(targetDirectory, ".proto");
        Files.createDirectories(targetDirectory);
        Set<String> names = new HashSet<>();
        List<Path> copied = new ArrayList<>();
        for (String source : sourceDirectories) {
            Path directory = Path.of(source);
            if (!Files.isDirectory(directory)) {
                throw new IOException("Proto source directory does not exist: " + directory);
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(item -> Files.isRegularFile(item)
                                && item.getFileName().toString().endsWith(".proto"))
                        .sorted().toList()) {
                    String fileName = path.getFileName().toString();
                    if (!names.add(fileName)) {
                        throw new IOException("Duplicate proto file name across source directories: " + fileName);
                    }
                    Path target = targetDirectory.resolve(fileName);
                    Files.copy(path, target);
                    copied.add(target);
                }
            }
        }
        return copied;
    }

    static void clearGeneratedDirectory(Path directory, String... extensions) throws IOException {
        clearFiles(directory, extensions);
    }

    static void run(List<String> command, String description) throws MojoExecutionException {
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new MojoExecutionException(description + " exited with code " + exitCode);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while running " + description, exception);
        } catch (IOException exception) {
            throw new MojoExecutionException("Failed to run " + description + ": " + command.getFirst(), exception);
        }
    }

    static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void clearFiles(Path directory, String... extensions) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Set<String> suffixes = Set.of(extensions);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> suffixes.stream().anyMatch(extension -> path.getFileName().toString().endsWith(extension)))
                    .sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
