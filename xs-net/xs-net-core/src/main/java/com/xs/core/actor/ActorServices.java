package com.xs.core.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.japi.function.Function;

import java.util.Objects;

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

    /** 返回 Actor 运行上下文。 */
    public ActorContext<M> context() {
        return context;
    }

    /** 返回 Actor 实体 ID。 */
    public String entityId() {
        return entityId;
    }

    /** 为消息处理方法恢复当前 RPC 请求上下文。 */
    public <T extends M> Function<T, Behavior<M>> contextual(Function<T, Behavior<M>> handler) {
        Function<T, Behavior<M>> checkedHandler = Objects.requireNonNull(handler, "handler");
        return message -> contextualizer.contextual(message, ignored -> checkedHandler.apply(message));
    }

    /** 向目标实体发送 RPC 消息。 */
    public void sendRpc(Class<?> roleClass, Object message, String targetEntityId) {
        rpcSender.send(
                Objects.requireNonNull(roleClass, "roleClass"),
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(targetEntityId, "targetEntityId"));
    }
}
