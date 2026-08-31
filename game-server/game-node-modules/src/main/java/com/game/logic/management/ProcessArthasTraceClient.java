package com.game.logic.management;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessArthasTraceClient implements ArthasTraceClient {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);

    private final Path clientJar;
    private final String javaCommand;
    private final int telnetPort;

    public ProcessArthasTraceClient(Path arthasHome, String javaCommand, int telnetPort) {
        this.clientJar = arthasHome.resolve("arthas-client.jar");
        this.javaCommand = javaCommand;
        this.telnetPort = telnetPort;
    }

    @Override
    public ArthasTraceExecution trace(ProtocolTraceTarget target, int timeoutMs) throws Exception {
        if (!Files.isRegularFile(clientJar)) {
            throw new IllegalStateException("Arthas client is unavailable: " + clientJar);
        }

        Process process = new ProcessBuilder(
                List.of(
                        javaCommand,
                        "-jar", clientJar.toString(),
                        "-t", String.valueOf(timeoutMs),
                        "-c", target.command(),
                        "127.0.0.1", String.valueOf(telnetPort)
                )
        ).redirectErrorStream(true).start();
        CompletableFuture<String> output = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                output.complete(readOutput(process.getInputStream()));
            } catch (IOException exception) {
                output.completeExceptionally(exception);
            }
        });

        boolean finished = process.waitFor(timeoutMs + SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (!finished) {
            process.getInputStream().close();
        }
        String traceOutput;
        try {
            traceOutput = output.get(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            traceOutput = output.getNow("[trace output unavailable: " + exception.getMessage() + "]");
        }
        return new ArthasTraceExecution(
                finished ? process.exitValue() : -1,
                !finished,
                traceOutput);
    }

    private static String readOutput(InputStream input) throws IOException {
        byte[] buffer = new byte[4096];
        StringBuilder output = new StringBuilder();
        int remaining = MAX_OUTPUT_BYTES;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (remaining > 0) {
                int length = Math.min(read, remaining);
                output.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                remaining -= length;
            }
        }
        if (remaining == 0) {
            output.append("\n[trace output truncated]\n");
        }
        return output.toString();
    }
}
