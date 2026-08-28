package com.xs.core.akka;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityContext;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessage;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@UtilityClass
public class AkkaKit {
    /** 分片实体专用调度器：实体 Actor 会做阻塞式 Redis 访问，用大容量线程池提升并发吞吐。 */
    public static final String ENTITY_DISPATCHER = "akka.actor.xs-entity-dispatcher";

    private static final Set<Class<?>> LOCAL = ConcurrentHashMap.newKeySet();
    private static final Map<Class<?>, EntityTypeKey<?>> ENTITY_TYPE_KEYS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> CREATE_METHODS = new ConcurrentHashMap<>();
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

    public static void tell(Class<?> actorCmdClass, Object message, String entityId) {
        tellToRole(actorCmdClass, message, entityId);
    }

    public static void tellRpc(Class<?> roleClass, RpcMessage message, String entityId) {
        tellToRole(roleClass, message, entityId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tellToRole(Class<?> roleClass, Object message, String entityId) {
        EntityRef entityRef = getClusterSharding().entityRefFor(entityTypeKey((Class) roleClass), entityId);
        Object deliveredMessage = LOCAL.contains(roleClass) ? message : MessageAble.toNetMessage(message);
        entityRef.tell(deliveredMessage);
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
        EntityTypeKey<?> typeKey = entityTypeKey(actorCmdClass);
        // 分片实体（PlayerMessage / S2C 通道）统一使用大容量专用调度器，
        // 实体 Actor 会做阻塞式 Redis 访问，默认 32 线程 fork-join 在高并发下成瓶颈。
        sharding.init(Entity.of(typeKey, entityContext -> createEntityContext(entityContext, actorClass))
                .withRole(roles)
                .withEntityProps(Props.empty().withDispatcherFromConfig(ENTITY_DISPATCHER)));
        LOCAL.add(actorCmdClass);
        log.info("初始化集群 Actor 实例 {} {}", actorCmdClass.getSimpleName(), roles);
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
            scala.collection.Iterator<String> iterator = akka.cluster.Cluster.get(system).selfRoles().iterator();
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
