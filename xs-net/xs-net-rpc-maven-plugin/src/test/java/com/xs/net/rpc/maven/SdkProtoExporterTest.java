package com.xs.net.rpc.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SdkProtoExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesEveryProtocolAndRemovesStaleFiles() throws Exception {
        Path firstSource = temporaryDirectory.resolve("first");
        Path secondSource = temporaryDirectory.resolve("second");
        Path target = temporaryDirectory.resolve("target");
        createDirectories(firstSource);
        createDirectories(secondSource);
        createDirectories(target);
        writeString(firstSource.resolve("one.proto"), "syntax = \"proto3\";");
        writeString(secondSource.resolve("two.proto"), "syntax = \"proto3\";");
        writeString(target.resolve("stale.proto"), "stale");

        List<Path> copied = SdkProtoExporter.copyProtoFiles(List.of(firstSource.toString(), secondSource.toString()), target);

        assertEquals(List.of("one.proto", "two.proto"), copied.stream().map(path -> path.getFileName().toString()).toList());
        assertEquals("syntax = \"proto3\";", readString(target.resolve("one.proto")));
        assertFalse(exists(target.resolve("stale.proto")));
    }

    @Test
    void rejectsDuplicateProtocolFileNames() throws Exception {
        Path firstSource = temporaryDirectory.resolve("first");
        Path secondSource = temporaryDirectory.resolve("second");
        createDirectories(firstSource);
        createDirectories(secondSource);
        writeString(firstSource.resolve("same.proto"), "syntax = \"proto3\";");
        writeString(secondSource.resolve("same.proto"), "syntax = \"proto3\";");

        assertThrows(IOException.class, () -> SdkProtoExporter.copyProtoFiles(
                List.of(firstSource.toString(), secondSource.toString()), temporaryDirectory.resolve("target")));
    }

    @Test
    void reportsMissingGeneratorExecutable() {
        MojoExecutionException exception = assertThrows(MojoExecutionException.class,
                () -> SdkProtoExporter.run(List.of("xs-net-command-that-does-not-exist"), "test generator"));
        assertEquals("Failed to run test generator: xs-net-command-that-does-not-exist", exception.getMessage());
    }
}
