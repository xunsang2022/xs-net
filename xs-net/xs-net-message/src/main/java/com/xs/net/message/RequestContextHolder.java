package com.xs.net.message;

import io.micrometer.core.instrument.Metrics;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Actor 处理期间的线程级请求上下文。
 *
 * <p>进入处理函数时同步写入 MDC，退出时恢复原上下文，避免线程池复用造成 traceId 串线。</p>
 */
@UtilityClass
public class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    /**
     * 获取当前线程的请求上下文。
     *
     * @return 当前请求上下文，如果未设置返回null
     */
    public static RequestContext current() {
        return CURRENT.get();
    }

    /**
     * 在指定请求上下文中执行操作，并同步更新MDC。
     * 执行完成后恢复原有的上下文和MDC，避免线程池复用造成traceId串线。
     *
     * @param context 请求上下文
     * @param action 要执行的操作
     * @param <T> 返回值类型
     * @return 操作返回值
     */
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
