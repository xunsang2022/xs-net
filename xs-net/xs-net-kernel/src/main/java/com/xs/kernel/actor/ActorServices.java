package com.xs.kernel.actor;

import com.xs.kernel.actor.node.Roles;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.japi.function.Function;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为 Actor 内部协作组件提供通用的 Actor 能力桥接。
 *
 * @param <M> Actor 接收的消息基类型
 */
public final class ActorServices<M> {

    /** 将消息处理方法包装为带请求上下文恢复能力的处理器。 */
    @FunctionalInterface
    public interface Contextualizer<M> {
        Behavior<M> contextual(M message, Function<M, Behavior<M>> handler);
    }

    /** 发送 RPC 消息的能力。 */
    @FunctionalInterface
    public interface RpcSender {
        void send(Class<?> roleClass, Object message, String targetEntityId);
    }

    /** 目标角色缓存：按消息类型缓存其 @Roles 接口，避免重复反射扫描。 */
    private static final Map<Class<?>, Class<?>> ROLE_CACHE = new ConcurrentHashMap<>();

    private final ActorContext<M> context;
    private final String entityId;
    private final Contextualizer<M> contextualizer;
    private final RpcSender rpcSender;

    /**
     * 创建 Actor 能力桥接对象。
     *
     * @param context Actor 运行上下文
     * @param entityId Actor 实体 ID
     * @param contextualizer 请求上下文包装器
     * @param rpcSender RPC 发送器
     */
    public ActorServices(
            ActorContext<M> context,
            String entityId,
            Contextualizer<M> contextualizer,
            RpcSender rpcSender
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.contextualizer = Objects.requireNonNull(contextualizer, "contextualizer");
        this.rpcSender = Objects.requireNonNull(rpcSender, "rpcSender");
    }

    /** 返回 Actor 实体 ID。 */
    public String entityId() {
        return entityId;
    }

    /** 直接为单条消息恢复 RPC 请求上下文并执行处理。 */
    public <T extends M> Behavior<M> contextualize(T message, Function<T, Behavior<M>> handler) {
        Function<T, Behavior<M>> checkedHandler = Objects.requireNonNull(handler, "handler");
        return contextualizer.contextual(message, ignored -> checkedHandler.apply(message));
    }

    /** 向目标实体发送 RPC 消息，目标角色取自消息类型实现的 @Roles 接口。 */
    public void sendRpc(String targetChannelId, Object message) {
        Class<?> messageType = Objects.requireNonNull(message, "message").getClass();
        rpcSender.send(roleOf(messageType), message,
                Objects.requireNonNull(targetChannelId, "targetChannelId"));
    }

    /** 解析消息类型实现的目标角色接口，必须唯一。 */
    static Class<?> roleOf(Class<?> messageType) {
        return ROLE_CACHE.computeIfAbsent(messageType, type -> {
            Set<Class<?>> roles = new LinkedHashSet<>();
            collectRoles(type, roles);
            if (roles.size() != 1) {
                throw new IllegalArgumentException("Message type must implement exactly one @Roles interface: "
                        + type.getName() + " -> " + roles);
            }
            return roles.iterator().next();
        });
    }

    /** 递归收集类及其父类、父接口上标注 @Roles 的接口。 */
    private static void collectRoles(Class<?> type, Set<Class<?>> roles) {
        if (type == null) {
            return;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (implemented.isAnnotationPresent(Roles.class)) {
                roles.add(implemented);
            }
            collectRoles(implemented, roles);
        }
        collectRoles(type.getSuperclass(), roles);
    }
}
