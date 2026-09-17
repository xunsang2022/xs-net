package com.xs.net.message;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中管理路由字段提取逻辑。
 *
 * <p>Gateway 只调用本类提供的提取器，不再自行反射猜测 DTO 中的 playerId 或 channelId。</p>
 */
@UtilityClass
public class RouteValueExtractors {
    private static final Map<MethodKey, Method> METHODS = new ConcurrentHashMap<>();

    public static RouteValueExtractor playerId() {
        return message -> invoke(message, "playerId");
    }

    public static RouteValueExtractor channelId() {
        return message -> {
            String channelId = invoke(message, "channelId");
            return channelId == null ? invoke(message, "sessionId") : channelId;
        };
    }

    private static String invoke(Object message, String methodName) {
        if (message == null) {
            return null;
        }
        Method method = METHODS.computeIfAbsent(new MethodKey(message.getClass(), methodName), key -> {
            try {
                return key.type().getMethod(key.name());
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        });
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(message);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private record MethodKey(Class<?> type, String name) {
    }
}
