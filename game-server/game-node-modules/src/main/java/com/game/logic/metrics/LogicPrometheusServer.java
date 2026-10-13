package com.game.logic.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.logic.management.GmProtocolTraceService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public final class LogicPrometheusServer implements AutoCloseable {

    private static final String GM_TOKEN_HEADER = "X-GM-Token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;

    private LogicPrometheusServer(HttpServer server) {
        this.server = server;
    }

    public static LogicPrometheusServer start(
            String host,
            int port,
            PrometheusMeterRegistry meterRegistry,
            String managementToken,
            GmProtocolTraceService traceService
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/metrics", exchange -> respond(exchange, meterRegistry));
        if (traceService != null && managementToken != null && !managementToken.isBlank()) {
            byte[] token = managementToken.getBytes(StandardCharsets.UTF_8);
            server.createContext("/admin/gm/trace", exchange -> trace(exchange, token, traceService));
        }
        server.start();
        return new LogicPrometheusServer(server);
    }

    private static void respond(HttpExchange exchange, PrometheusMeterRegistry meterRegistry) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] payload = meterRegistry.scrape().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static void trace(
            HttpExchange exchange,
            byte[] managementToken,
            GmProtocolTraceService traceService
    ) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String providedToken = exchange.getRequestHeaders().getFirst(GM_TOKEN_HEADER);
        if (providedToken == null || !MessageDigest.isEqual(
                managementToken,
                providedToken.getBytes(StandardCharsets.UTF_8))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }

        try {
            TraceRequest request = JSON.readValue(exchange.getRequestBody(), TraceRequest.class);
            GmProtocolTraceService.TraceResult result = traceService.trace(
                    request.protocolNumber(), request.timeoutMs());
            int status = "BUSY".equals(result.status()) ? 409 : "FAILED".equals(result.status()) ? 503 : 200;
            respondJson(exchange, status, result);
        } catch (IllegalArgumentException exception) {
            respondJson(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            respondJson(exchange, 500, Map.of("error", "Unable to process GM trace request"));
        }
    }

    private static void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private record TraceRequest(int protocolNumber, Integer timeoutMs) {
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
