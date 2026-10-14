package com.game.logic.management;

import com.game.logic.actor.gm.GMActor;
import com.game.logic.actor.player.PlayerActor;
import com.game.message.actor.logic.GMMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.xs.kernel.actor.node.Roles;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/** 根据外部 RPC 编号解析 Logic Actor 处理方法。 */
@RequiredArgsConstructor
public final class ProtocolTraceTargetResolver {

    private static final Map<Class<?>, Class<?>> ACTORS_BY_ROLE = Map.of(
            PlayerMessage.class, PlayerActor.class,
            GMMessage.class, GMActor.class
    );

    /** 启动阶段生成的只读 RPC 注册表。 */
    @NonNull
    private final RpcMessageRegistry registry;

    public ProtocolTraceTarget resolve(int protocolNumber) {
        RpcMessageSpec<?> spec = registry.find(protocolNumber);
        if (spec == null) {
            throw new IllegalArgumentException("Unknown protocol number: " + protocolNumber);
        }
        if (spec.dtoClass() == null || spec.roleClass() == null) {
            throw new IllegalArgumentException("Protocol does not enter a Logic actor: " + protocolNumber);
        }
        Roles role = spec.roleClass().getAnnotation(Roles.class);
        if (role == null || !"logic".equals(role.value())) {
            throw new IllegalArgumentException("Protocol does not enter the Logic node: " + protocolNumber);
        }

        Class<?> actorType = ACTORS_BY_ROLE.get(spec.roleClass());
        if (actorType == null) {
            throw new IllegalArgumentException("No Logic actor is registered for protocol: " + protocolNumber);
        }
        // 方法名改由 node-modules 自身生成的 handler 元数据（rpcNum → 方法名）提供。
        String methodName = GeneratedRpcHandlers.methodNames().get(protocolNumber);
        if (methodName == null) {
            throw new IllegalArgumentException("Cannot resolve Logic handler for protocol: " + protocolNumber);
        }
        return new ProtocolTraceTarget(
                protocolNumber,
                protocolNumber,
                spec.dtoClass(),
                actorType,
                methodName);
    }
}
