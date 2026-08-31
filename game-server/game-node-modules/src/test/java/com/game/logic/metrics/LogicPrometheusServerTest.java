package com.game.logic.metrics;

import com.game.logic.management.ArthasTraceExecution;
import com.game.logic.management.GmProtocolTraceService;
import com.game.logic.management.ProtocolTraceTargetResolver;
import com.game.message.rpc.GameRpcMessages;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicPrometheusServerTest {

    @Test
    void exposesPrometheusMetrics() throws Exception {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Counter.builder("logic.test.counter").register(meterRegistry).increment();

        try (LogicPrometheusServer server = LogicPrometheusServer.start("127.0.0.1", 0, meterRegistry)) {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/metrics"))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("logic_test_counter_total"));
        }
    }

    @Test
    void returnsTraceOutputOnlyForAuthenticatedGmRequest() throws Exception {
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        GmProtocolTraceService traceService = new GmProtocolTraceService(
                new ProtocolTraceTargetResolver(GameRpcMessages.INSTANCE),
                (target, timeoutMs) -> new ArthasTraceExecution(0, false, "trace output"));

        try (LogicPrometheusServer server = LogicPrometheusServer.start(
                "127.0.0.1", 0, meterRegistry, "test-token", traceService)) {
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/admin/gm/trace");
            HttpRequest unauthorized = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"protocolNumber\":1001}"))
                    .build();
            HttpResponse<String> unauthorizedResponse = HttpClient.newHttpClient().send(
                    unauthorized, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorizedResponse.statusCode());

            HttpRequest authorized = HttpRequest.newBuilder(uri)
                    .header("X-GM-Token", "test-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"protocolNumber\":1001,\"timeoutMs\":1000}"))
                    .build();
            HttpResponse<String> authorizedResponse = HttpClient.newHttpClient().send(
                    authorized, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, authorizedResponse.statusCode());
            assertTrue(authorizedResponse.body().contains("COMPLETED"));
            assertTrue(authorizedResponse.body().contains("onLogoutMessage"));
        }
    }
}
