package com.game.logic.redis;

import com.xs.kernel.model.XsModel;
import org.springframework.data.redis.hash.HashMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Maps {@link XsModel} instances to Redis hash fields without model-specific conversion code. */
public final class XsModelHashMapper<T extends XsModel<T>> implements HashMapper<T, String, Object> {

    private final Class<T> modelType;
    private final Constructor<T> constructor;

    public XsModelHashMapper(Class<T> modelType) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
        try {
            this.constructor = modelType.getDeclaredConstructor();
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("Model must have a no-argument constructor: "
                    + modelType.getName(), exception);
        }
    }

    @Override
    public Map<String, Object> toHash(T model) {
        return Objects.requireNonNull(model, "model").toValueMap();
    }

    public Map<String, Object> toHash(T model, Collection<String> fields) {
        return Objects.requireNonNull(model, "model").toValueMap(fields);
    }

    public T fromStringHash(Map<String, String> hash) {
        Map<String, Object> values = new LinkedHashMap<>(hash);
        return fromHash(values);
    }

    @Override
    public T fromHash(Map<String, Object> hash) {
        try {
            T model = constructor.newInstance();
            model.fromHash(hash);
            return model;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to create model: " + modelType.getName(), exception);
        }
    }
}
