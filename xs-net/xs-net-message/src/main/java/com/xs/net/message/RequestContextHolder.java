package com.xs.net.message;

import io.micrometer.core.instrument.Metrics;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Thread-scoped view of the context while an Actor handler is executing. */
@UtilityClass
public class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    public static RequestContext current() {
        return CURRENT.get();
    }

    public static <T> T call(RequestContext context, Supplier<T> action) {
        RequestContext previous = CURRENT.get();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        if (context == null) {
            CURRENT.remove();
            clearMdc();
        } else {
            CURRENT.set(context);
            putMdc(context);
            Metrics.counter("xs.rpc.context.calls",
                    "source_node", value(context.sourceNode()),
                    "schema_version", String.valueOf(context.schemaVersion())).increment();
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private static void putMdc(RequestContext context) {
        Map<String, String> values = new HashMap<>();
        values.put("traceId", context.traceId());
        values.put("hopCount", String.valueOf(context.hopCount()));
        if (context.playerId() != null && !context.playerId().isBlank()) {
            values.put("playerId", context.playerId());
        }
        if (context.channelId() != null && !context.channelId().isBlank()) {
            values.put("channelId", context.channelId());
        }
        if (context.sourceNode() != null && !context.sourceNode().isBlank()) {
            values.put("sourceNode", context.sourceNode());
        }
        MDC.setContextMap(values);
    }

    private static void clearMdc() {
        MDC.clear();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
