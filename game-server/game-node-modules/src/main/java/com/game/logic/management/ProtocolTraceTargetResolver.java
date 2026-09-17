package com.game.logic.management;

import com.game.logic.actor.GMActor;
import com.game.logic.actor.PlayerActor;
import com.game.logic.actor.SceneActor;
import com.game.message.actor.logic.GMMessage;
import com.game.message.actor.logic.PlayerMessage;
import com.game.message.actor.logic.SceneMessage;
import com.xs.core.actor.node.Roles;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.pekko.actor.typed.Behavior;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 根据外部 RPC 编号解析 Logic Actor 处理方法。 */
@RequiredArgsConstructor
public final class ProtocolTraceTargetResolver {

    private static final Map<Integer, Integer> PROTOCOL_ALIASES = Map.of(1000, 3003);
    private static final Map<Class<?>, Class<?>> ACTORS_BY_ROLE = Map.of(
            PlayerMessage.class, PlayerActor.class,
            SceneMessage.class, SceneActor.class,
            GMMessage.class, GMActor.class
    );

    /** 启动阶段生成的只读 RPC 注册表。 */
    @NonNull
    private final RpcMessageRegistry registry;

    public ProtocolTraceTarget resolve(int protocolNumber) {
        int effectiveProtocolNumber = PROTOCOL_ALIASES.getOrDefault(protocolNumber, protocolNumber);
        RpcMessageSpec<?> spec = registry.find(effectiveProtocolNumber);
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
        return new ProtocolTraceTarget(
                protocolNumber,
                effectiveProtocolNumber,
                spec.dtoClass(),
                actorType,
                findHandler(actorType, spec.dtoClass(), protocolNumber));
    }

    private static String findHandler(Class<?> actorType, Class<?> messageType, int protocolNumber) {
        List<String> handlers = Arrays.stream(actorType.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("on"))
                .filter(method -> method.getParameterCount() == 1)
                .filter(method -> method.getParameterTypes()[0].equals(messageType))
                .filter(method -> Behavior.class.isAssignableFrom(method.getReturnType()))
                .map(Method::getName)
                .sorted()
                .toList();
        if (handlers.size() != 1) {
            throw new IllegalArgumentException("Cannot resolve a unique Logic handler for protocol: " + protocolNumber);
        }
        return handlers.getFirst();
    }
}
