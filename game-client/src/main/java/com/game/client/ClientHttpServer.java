package com.game.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientHttpServer implements AutoCloseable {

    private final GameClientApplication.ClientSettings settings;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, GameTestClient> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;

    public ClientHttpServer(GameClientApplication.ClientSettings settings) throws IOException {
        this.settings = settings;
        this.server = HttpServer.create(new InetSocketAddress(settings.httpHost(), settings.httpPort()), 0);
        server.createContext("/sessions", this::handleSessions);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        sessions.values().forEach(this::closeQuietly);
        sessions.clear();
    }

    private void handleSessions(HttpExchange exchange) throws IOException {
        try {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length != 4 || parts[2].isBlank()) {
                write(exchange, 404, Map.of("error", "Unknown endpoint"));
                return;
            }
            String sessionId = parts[2];
            switch (parts[3]) {
                case "connect" -> connect(exchange, sessionId);
                case "messages" -> send(exchange, sessionId);
                default -> write(exchange, 404, Map.of("error", "Unknown endpoint"));
            }
        } catch (IllegalArgumentException exception) {
            write(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (IOException exception) {
            write(exchange, 502, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            write(exchange, 500, Map.of("error", "Unexpected client error"));
        } finally {
            exchange.close();
        }
    }

    private void connect(HttpExchange exchange, String sessionId) throws IOException {
        requireMethod(exchange, "POST");
        JsonNode body = objectMapper.readTree(exchange.getRequestBody());
        String token = body.path("token").asText();
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        GameTestClient client = new GameTestClient(settings);
        try {
            client.connect(token);
        } catch (IOException exception) {
            closeQuietly(client);
            throw exception;
        }
        GameTestClient previous = sessions.put(sessionId, client);
        closeQuietly(previous);
        write(exchange, 201, Map.of("sessionId", sessionId));
    }

    private void send(HttpExchange exchange, String sessionId) throws IOException {
        requireMethod(exchange, "POST");
        GameTestClient client = sessions.get(sessionId);
        if (client == null) {
            write(exchange, 404, Map.of("error", "Unknown session: " + sessionId));
            return;
        }
        JsonNode body = objectMapper.readTree(exchange.getRequestBody());
        if (!body.has("rpcNum") || !body.has("payload")) {
            throw new IllegalArgumentException("rpcNum and payload are required");
        }
        client.send(body.get("rpcNum").asInt(), body.get("payload"));
        write(exchange, 202, Map.of("sessionId", sessionId));
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equals(exchange.getRequestMethod())) {
            throw new IllegalArgumentException("Method must be " + expected);
        }
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void closeQuietly(GameTestClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
