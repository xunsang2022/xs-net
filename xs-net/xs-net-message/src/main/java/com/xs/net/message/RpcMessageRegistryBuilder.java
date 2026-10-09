package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RPC消息注册表构建器，用于注册和构建不可变的RPC消息注册表。
 * 支持按RPC编号、消息类和DTO类进行索引。
 */
public final class RpcMessageRegistryBuilder {
    /** 按RPC编号索引的消息规范映射 */
    private final Map<Integer, RpcMessageSpec<? extends MessageLite>> byRpcNum = new LinkedHashMap<>();
    /** 按消息类索引的消息规范映射 */
    private final Map<Class<? extends MessageLite>, RpcMessageSpec<? extends MessageLite>> byMessageClass = new LinkedHashMap<>();
    /** 按DTO类索引的消息规范映射 */
    private final Map<Class<?>, RpcMessageSpec<? extends MessageLite>> byDtoClass = new LinkedHashMap<>();

    /**
     * 注册单个RPC消息规范。
     *
     * @param spec RPC消息规范
     * @return 当前构建器实例
     * @throws IllegalArgumentException 如果存在重复的RPC编号、消息类或DTO类
     */
    public RpcMessageRegistryBuilder register(RpcMessageSpec<? extends MessageLite> spec) {
        Objects.requireNonNull(spec, "spec");
        registerUnique(byRpcNum, spec.rpcNum(), spec, "rpc number");
        registerUnique(byMessageClass, spec.messageClass(), spec, "protobuf class");
        if (spec.dtoClass() != null) {
            registerUnique(byDtoClass, spec.dtoClass(), spec, "dto class");
        }
        return this;
    }

    /**
     * 批量注册RPC消息规范。
     *
     * @param specs RPC消息规范集合
     * @return 当前构建器实例
     */
    public RpcMessageRegistryBuilder registerAll(Collection<? extends RpcMessageSpec<? extends MessageLite>> specs) {
        Objects.requireNonNull(specs, "specs");
        specs.forEach(this::register);
        return this;
    }

    /**
     * 构建不可变的RPC消息注册表。
     *
     * @return RPC消息注册表
     */
    public RpcMessageRegistry build() {
        List<RpcMessageSpec<? extends MessageLite>> specs = List.copyOf(new ArrayList<>(byRpcNum.values()));
        return new ImmutableRpcMessageRegistry(specs, Map.copyOf(byRpcNum), Map.copyOf(byMessageClass), Map.copyOf(byDtoClass));
    }

    private static <K> void registerUnique(
            Map<K, RpcMessageSpec<? extends MessageLite>> target,
            K key,
            RpcMessageSpec<? extends MessageLite> spec,
            String kind
    ) {
        RpcMessageSpec<? extends MessageLite> previous = target.putIfAbsent(key, spec);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate " + kind + ": " + key);
        }
    }

    private record ImmutableRpcMessageRegistry(
            List<RpcMessageSpec<? extends MessageLite>> specs,
            Map<Integer, RpcMessageSpec<? extends MessageLite>> byRpcNum,
            Map<Class<? extends MessageLite>, RpcMessageSpec<? extends MessageLite>> byMessageClass,
            Map<Class<?>, RpcMessageSpec<? extends MessageLite>> byDtoClass
    ) implements RpcMessageRegistry {
        @Override
        public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
            return byRpcNum.get(rpcNum);
        }

        @Override
        public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
            return byMessageClass.get(messageClass);
        }

        @Override
        public List<RpcMessageSpec<? extends MessageLite>> all() {
            return specs;
        }

        @Override
        public RpcMessageSpec<? extends MessageLite> findByDtoClass(Class<?> dtoClass) {
            return byDtoClass.get(dtoClass);
        }
    }
}
