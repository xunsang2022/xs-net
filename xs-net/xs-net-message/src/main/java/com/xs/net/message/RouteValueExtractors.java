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

    /**
     * 创建玩家ID提取器。
     *
     * @return 玩家ID提取器
     */
    public static RouteValueExtractor playerId() {
        return message -> invoke(message, "playerId");
    }

    /**
     * 创建频道ID提取器，优先使用channelId，如果为null则使用sessionId。
     *
     * @return 频道ID提取器
     */
    public static RouteValueExtractor channelId() {
        return message -> {
            String channelId = invoke(message, "channelId");
            return channelId == null ? invoke(message, "sessionId") : channelId;
        };
    }

    /**
     * 通过反射调用消息对象的指定方法获取路由值。
     *
     * @param message 消息对象
     * @param methodName 方法名
     * @return 路由值，如果方法不存在或调用失败返回null
     */
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
