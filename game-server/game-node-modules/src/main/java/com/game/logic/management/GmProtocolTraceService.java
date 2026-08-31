package com.game.logic.management;

import java.util.concurrent.Semaphore;

public final class GmProtocolTraceService {

    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MIN_TIMEOUT_MS = 1_000;
    private static final int MAX_TIMEOUT_MS = 30_000;

    private final ProtocolTraceTargetResolver targetResolver;
    private final ArthasTraceClient traceClient;
    private final Semaphore activeTrace = new Semaphore(1);

    public GmProtocolTraceService(ProtocolTraceTargetResolver targetResolver, ArthasTraceClient traceClient) {
        this.targetResolver = targetResolver;
        this.traceClient = traceClient;
    }

    public TraceResult trace(int protocolNumber, Integer requestedTimeoutMs) {
        int timeoutMs = normalizeTimeout(requestedTimeoutMs);
        ProtocolTraceTarget target = targetResolver.resolve(protocolNumber);
        if (!activeTrace.tryAcquire()) {
            return TraceResult.busy(target, timeoutMs);
        }
        try {
            ArthasTraceExecution execution = traceClient.trace(target, timeoutMs);
            String status = execution.timedOut() ? "TIMED_OUT" : execution.exitCode() == 0 ? "COMPLETED" : "FAILED";
            return new TraceResult(target, timeoutMs, status, execution.exitCode(), execution.output());
        } catch (Exception exception) {
            return new TraceResult(target, timeoutMs, "FAILED", -1, exception.getMessage());
        } finally {
            activeTrace.release();
        }
    }

    private static int normalizeTimeout(Integer requestedTimeoutMs) {
        int timeoutMs = requestedTimeoutMs == null ? DEFAULT_TIMEOUT_MS : requestedTimeoutMs;
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS);
        }
        return timeoutMs;
    }

    public record TraceResult(
            ProtocolTraceTarget target,
            int timeoutMs,
            String status,
            int exitCode,
            String output
    ) {
        private static TraceResult busy(ProtocolTraceTarget target, int timeoutMs) {
            return new TraceResult(target, timeoutMs, "BUSY", -1, "Another GM protocol trace is already running.");
        }
    }
}
