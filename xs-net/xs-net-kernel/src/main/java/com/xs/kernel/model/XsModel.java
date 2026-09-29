package com.xs.kernel.model;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * xs-net 对业务模型的持久化无关契约。
 *
 * <p>通用模型方法通过默认实现委托给 {@link XsModelSupport}，业务侧可在
 * 保留自身 ORM 基类的同时复用这些能力。</p>
 *
 * @param <M> 具体模型类型
 */
public interface XsModel<M extends XsModel<M>> {

    /** 方法引用式 setter。 */
    @FunctionalInterface
    interface ModelPropertySetter<M extends XsModel<M>, V> extends Serializable {
        void apply(M model, V value);
    }

    /** 由业务模型提供的通用能力支持对象。 */
    XsModelSupport xsModelSupport();

    @SuppressWarnings("unchecked")
    private M self() {
        return (M) this;
    }

    default <V> M setProperty(ModelPropertySetter<M, V> setter, V value) {
        return xsModelSupport().setProperty(self(), setter, value);
    }

    default void markDirty(String field) {
        xsModelSupport().markDirty(field);
    }

    default List<String> getDirtyFields() {
        return xsModelSupport().getDirtyFields();
    }

    default boolean hasDirtyFields() {
        return xsModelSupport().hasDirtyFields();
    }

    default void clearDirty() {
        xsModelSupport().clearDirty();
    }

    default void clearDirty(Collection<String> fields) {
        xsModelSupport().clearDirty(fields);
    }

    default void clearDirtyMatching(Map<String, String> persistedFields) {
        xsModelSupport().clearDirtyMatching(persistedFields);
    }

    default String fieldValue(String field) {
        return xsModelSupport().fieldValue(field);
    }

    default Map<String, String> toHash() {
        return xsModelSupport().toHash();
    }

    default Map<String, String> toHash(Collection<String> fields) {
        return xsModelSupport().toHash(fields);
    }

    default Map<String, Object> toValueMap() {
        return xsModelSupport().toValueMap();
    }

    default Map<String, Object> toValueMap(Collection<String> fields) {
        return xsModelSupport().toValueMap(fields);
    }

    default void fromHash(Map<?, ?> fields) {
        xsModelSupport().fromHash(fields);
    }
}
