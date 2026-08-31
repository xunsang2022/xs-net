package com.xs.core.spring;

import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public final class ClassUtil {
    private ClassUtil() {
    }

    public static Class<?> findMessageInterface(Class<?> actorClass) {
        Class<?> messageClass = extractMessageTypeFromGenerics(actorClass);
        if (messageClass != null) {
            return messageClass;
        }
        for (Class<?> iface : actorClass.getInterfaces()) {
            if (isMessageType(iface)) {
                return iface;
            }
        }
        Class<?> superClass = actorClass.getSuperclass();
        return superClass == null || Object.class.equals(superClass) ? null : findMessageInterface(superClass);
    }

    public static Class<?> extractMessageTypeFromGenerics(Class<?> actorClass) {
        try {
            return findAbstractBehaviorMessageType(actorClass, new HashMap<>());
        } catch (Exception e) {
            log.warn("Unable to extract message type from actor class: {}", actorClass.getName(), e);
            return null;
        }
    }

    private static Class<?> findAbstractBehaviorMessageType(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        if (type == null || Object.class.equals(type)) {
            return null;
        }
        Type genericSuperclass = type.getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType parameterizedType) {
            Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            TypeVariable<?>[] variables = rawType.getTypeParameters();
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Map<TypeVariable<?>, Type> resolvedBindings = new HashMap<>(bindings);
            for (int index = 0; index < variables.length; index++) {
                resolvedBindings.put(variables[index], resolve(arguments[index], bindings));
            }
            if (AbstractBehavior.class.equals(rawType)) {
                return toClass(resolve(arguments[0], bindings));
            }
            return findAbstractBehaviorMessageType(rawType, resolvedBindings);
        }
        return findAbstractBehaviorMessageType(type.getSuperclass(), bindings);
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        while (type instanceof TypeVariable<?> variable && bindings.containsKey(variable)) {
            type = bindings.get(variable);
        }
        return type;
    }

    private static Class<?> toClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return isMessageType(clazz) ? clazz : null;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return isMessageType(clazz) ? clazz : null;
        }
        return null;
    }

    private static boolean isMessageType(Class<?> type) {
        Package typePackage = type.getPackage();
        if (typePackage == null) {
            return false;
        }
        String packageName = typePackage.getName();
        return packageName.contains(".actor") || packageName.contains(".message");
    }
}
