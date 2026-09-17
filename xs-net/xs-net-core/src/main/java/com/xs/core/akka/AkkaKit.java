package com.xs.core.akka;

import com.google.protobuf.MessageLite;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityContext;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessage;
import com.xs.net.message.RequestContext;
import com.xs.net.message.pb.NetMessage;
import io.micrometer.core.instrument.Metrics;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@UtilityClass
public class AkkaKit {
    /** 分片实体专用调度器：实体 Actor 会做阻塞式 Redis 访问，用大容量线程池提升并发吞吐。 */
    public static final String ENTITY_DISPATCHER = "pekko.actor.xs-entity-dispatcher";

    private static final Map<Class<?>, EntityTypeKey<?>> ENTITY_TYPE_KEYS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> CREATE_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, RpcDeliveryInterceptor> DELIVERY_INTERCEPTORS = new ConcurrentHashMap<>();
    private static final String METRIC_ROUTING_REJECTED = "xs.rpc.routing.rejected";
    private static volatile ClusterSharding clusterSharding;

    public static void setClusterSharding(ClusterSharding sharding) {
        clusterSharding = sharding;
    }

    public static ClusterSharding getClusterSharding() {
        ClusterSharding sharding = clusterSharding;
        if (sharding == null) {
            throw new IllegalStateException("ClusterSharding has not been initialized");
        }
        return sharding;
    }

    public static void registerDeliveryInterceptor(Class<?> roleClass, RpcDeliveryInterceptor interceptor) {
        DELIVERY_INTERCEPTORS.put(Objects.requireNonNull(roleClass, "roleClass"),
                Objects.requireNonNull(interceptor, "interceptor"));
    }

    public static void unregisterDeliveryInterceptor(Class<?> roleClass) {
        if (roleClass != null) {
            DELIVERY_INTERCEPTORS.remove(roleClass);
        }
    }

    public static void tell(Class<?> actorCmdClass, Object message, String entityId) {
        tell(MessageAble.registry(), actorCmdClass, message, entityId);
    }

    public static void tell(
            RpcMessageRegistry registry,
            Class<?> actorCmdClass,
            Object message,
            String entityId
    ) {
        tellToRole(registry, actorCmdClass, message, entityId, null);
    }

    public static void tell(
            RpcMessageRegistry registry,
            Class<?> actorCmdClass,
            Object message,
            String entityId,
            RequestContext context
    ) {
        tellToRole(registry, actorCmdClass, message, entityId, context);
    }

    public static void tellRpc(Class<?> roleClass, RpcMessage message, String entityId) {
        tell(MessageAble.registry(), roleClass, message, entityId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tellToRole(
            RpcMessageRegistry registry,
            Class<?> roleClass,
            Object message,
            String entityId,
            RequestContext context
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(roleClass, "roleClass");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(entityId, "entityId");
        RpcMessageSpec<? extends MessageLite> spec = findSpec(registry, message);
        if (spec == null) {
            throw rejectRouting("unknown_rpc", "No RPC definition for message type "
                    + message.getClass().getName());
        }
        if (!roleClass.equals(spec.roleClass())) {
            throw rejectRouting("role_mismatch", "RPC " + spec.rpcNum() + " belongs to role "
                    + (spec.roleClass() == null ? "null" : spec.roleClass().getName())
                    + ", but was sent to " + roleClass.getName());
        }
        if (message instanceof RpcMessage rpcMessage
                && (rpcMessage.payload() == null || !spec.messageClass().isInstance(rpcMessage.payload()))) {
            throw rejectRouting("payload_type_mismatch", "RPC " + spec.rpcNum()
                    + " payload type does not match " + spec.messageClass().getName());
        }
        RpcDeliveryInterceptor interceptor = DELIVERY_INTERCEPTORS.get(roleClass);
        if (interceptor != null && interceptor.intercept(registry, roleClass, message, entityId, context)) {
            return;
        }
        EntityRef entityRef = getClusterSharding().entityRefFor(entityTypeKey((Class) roleClass), entityId);
        // Entity placement can change at runtime. Always use the protocol envelope so a local
        // sender cannot accidentally deliver a non-serializable DTO to a remote shard.
        NetMessage deliveredMessage = message instanceof NetMessage netMessage
                ? netMessage
                : MessageAble.toNetMessage(message, registry);
        if (context != null) {
            deliveredMessage = context.applyTo(deliveredMessage.toBuilder()).build();
        }
        entityRef.tell(deliveredMessage);
    }

    private static RpcMessageSpec<? extends MessageLite> findSpec(
            RpcMessageRegistry registry,
            Object message
    ) {
        if (message instanceof NetMessage netMessage) {
            return registry.find(netMessage.getRpcNum());
        }
        if (message instanceof RpcMessage rpcMessage) {
            return registry.find(rpcMessage.rpcNum());
        }
        return registry.findByDtoClass(message.getClass());
    }

    private static IllegalArgumentException rejectRouting(String reason, String message) {
        Metrics.counter(METRIC_ROUTING_REJECTED, "reason", reason).increment();
        log.warn("Rejecting RPC route: reason={}, message={}", reason, message);
        return new IllegalArgumentException(message);
    }

    public static <T> void entityRefForClusterSharding(ClusterSharding sharding, String roles, Class<T> actorCmdClass) {
        sharding.init(Entity.of(entityTypeKey(actorCmdClass), entityContext -> Behaviors.receive(actorCmdClass)
                .onAnyMessage(message -> Behaviors.same()).build())
                .withRole(roles)
                .withEntityProps(Props.empty().withDispatcherFromConfig(ENTITY_DISPATCHER)));
        log.info("初始化集群Actor代理 {} {}", actorCmdClass.getSimpleName(), roles);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initClusterSharding(ClusterSharding sharding, Class<?> actorCmdClass, Class<?> actorClass, String roles) {
        initClusterSharding(sharding, actorCmdClass, actorClass, roles, ENTITY_DISPATCHER);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initClusterSharding(
            ClusterSharding sharding,
            Class<?> actorCmdClass,
            Class<?> actorClass,
            String roles,
            String dispatcher
    ) {
        EntityTypeKey<?> typeKey = entityTypeKey(actorCmdClass);
        // 分片实体（PlayerMessage / S2C 通道）统一使用大容量专用调度器，
        // 实体 Actor 会做阻塞式 Redis 访问，默认 32 线程 fork-join 在高并发下成瓶颈。
        sharding.init(Entity.of(typeKey, entityContext -> createEntityContext(entityContext, actorClass))
                .withRole(roles)
                .withEntityProps(Props.empty().withDispatcherFromConfig(Objects.requireNonNull(dispatcher, "dispatcher"))));
        log.info("初始化集群 Actor 实例 {} {} dispatcher={}", actorCmdClass.getSimpleName(), roles, dispatcher);
    }

    @SuppressWarnings("unchecked")
    private static <M> Behavior<M> createEntityContext(EntityContext<M> context, Class<?> actorClass) {
        try {
            Method create = CREATE_METHODS.computeIfAbsent(actorClass, AkkaKit::resolveCreateMethod);
            Object argument = create.getParameterCount() == 1 ? context.getEntityId() : null;
            return (Behavior<M>) create.invoke(null, argument);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("创建 Actor 失败：" + actorClass.getSimpleName(), e);
        }
    }

    private static Method resolveCreateMethod(Class<?> actorClass) {
        try {
            return actorClass.getMethod("create");
        } catch (NoSuchMethodException e) {
            try {
                return actorClass.getMethod("create", String.class);
            } catch (NoSuchMethodException nested) {
                throw new RuntimeException("创建 Actor 失败：" + actorClass.getSimpleName(), nested);
            }
        }
    }

    public static Set<String> getCurrentNodeRoles(ActorSystem<?> system) {
        Set<String> roles = new HashSet<>();
        try {
            scala.collection.Iterator<String> iterator = org.apache.pekko.cluster.Cluster.get(system).selfRoles().iterator();
            while (iterator.hasNext()) {
                roles.add(iterator.next());
            }
        } catch (Exception e) {
            log.warn("获取节点角色失败，使用默认角色", e);
        }
        return roles;
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityTypeKey<T> entityTypeKey(Class<T> actorCmdClass) {
        return (EntityTypeKey<T>) ENTITY_TYPE_KEYS.computeIfAbsent(actorCmdClass,
                key -> EntityTypeKey.create(key, key.getSimpleName()));
    }
}
