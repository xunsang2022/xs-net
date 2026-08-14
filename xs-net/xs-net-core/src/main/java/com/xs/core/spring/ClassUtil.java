package com.xs.core.spring;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClassUtil {
    /**
     * 查找 Actor 的消息接口类
     * 从 XsActor 或 AbstractBehavior 的泛型参数中提取消息类型
     */
    public static Class<?> findMessageInterface(Class<?> actorClass) {
        // 尝试从泛型参数中获取消息类型
        Class<?> messageClass = extractMessageTypeFromGenerics(actorClass);
        if (messageClass != null) {
            return messageClass;
        }

        // 查找所有实现的接口
        for (Class<?> iface : actorClass.getInterfaces()) {
            // 检查是否是消息接口（通常在 game-message-action 包中）
            if (iface.getPackage() != null &&
                    iface.getPackage().getName().contains(".actor")) {
                return iface;
            }
        }

        // 如果没有直接实现消息接口，尝试从父类查找
        Class<?> superClass = actorClass.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            return findMessageInterface(superClass);
        }

        return null;
    }

    /**
     * 从泛型参数中提取消息类型
     * 例如：XsActor<PlayerMessage, User, Long> -> PlayerMessage
     */
    public static Class<?> extractMessageTypeFromGenerics(Class<?> actorClass) {
        try {
            java.lang.reflect.Type genericSuperclass = actorClass.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType parameterizedType =
                        (java.lang.reflect.ParameterizedType) genericSuperclass;
                java.lang.reflect.Type[] typeArguments = parameterizedType.getActualTypeArguments();

                // 第一个泛型参数通常是消息类型
                if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                    Class<?> messageClass = (Class<?>) typeArguments[0];
                    // 验证是否是消息接口（在 .actor 或 .message 包中）
                    if (messageClass.getPackage() != null) {
                        String packageName = messageClass.getPackage().getName();
                        if (packageName.contains(".actor") || packageName.contains(".message")) {
                            return messageClass;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Unable to extract message type from actor class: {}", actorClass.getName(), e);
        }
        return null;
    }
}
