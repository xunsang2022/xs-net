package com.xs.core.akka;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.*;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessage;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@UtilityClass
public class AkkaKit {
    public static final List<Class<?>> local = new ArrayList<>();
    private static final Map<Class<?>, EntityTypeKey<?>> ENTITY_TYPE_KEYS = new ConcurrentHashMap<>();
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


    /**
     * 发送消息到集群
     * <p/>
     *
     * @param actorCmdClass
     * @param message
     * @param entityId
     * @throws IOException
     */
    @SuppressWarnings("all")
    public <T> void tell(
            Class<T> actorCmdClass,
            T message,
            String entityId
    ) {
        EntityTypeKey<T> entityTypeKey = entityTypeKey(actorCmdClass);
        EntityRef<T> entityRef = getClusterSharding().entityRefFor(entityTypeKey, entityId);
        Object sedMessage;
        if (local.contains(actorCmdClass)) {
            sedMessage = message;
        } else if (message instanceof RpcMessage rpcMessage) {
            sedMessage = MessageAble.toNetMessage(rpcMessage);
        } else {
            throw new IllegalArgumentException(
                    "Remote messages must implement RpcMessage: " + message.getClass().getName());
        }
        entityRef.tell((T) sedMessage);
    }

    public <T> void entityRefForClusterSharding(ClusterSharding clusterSharding, String roles, Class<T> actorCmdClass) {
        clusterSharding.init(
                Entity.of(entityTypeKey(actorCmdClass),
                        entityContext -> Behaviors.receive(actorCmdClass)
                                .onAnyMessage(msg -> Behaviors.same())
                                .build()
                ).withRole(roles)
        );
        log.info("初始化集群Actor代理 {} {}", actorCmdClass.getSimpleName(), roles);
    }

    public static void initClusterSharding(ClusterSharding clusterSharding, Class<?> actorCmdClass, Class<?> actorClass, String roles) {
        EntityTypeKey<?> typeKey = entityTypeKey(actorCmdClass);
        clusterSharding.init(Entity.of(
                typeKey,
                entityContext -> createEntityContext(entityContext, actorClass)
        ).withRole(roles));
        local.add(actorCmdClass);
        log.info("初始化集群 Actor 实例 {} {}", actorCmdClass.getSimpleName(), roles);
    }

    @SuppressWarnings("unchecked")
    private static <M> Behavior<M> createEntityContext(EntityContext<M> context, Class<?> actorClass) {
        try {
            // 优先尝试无参 create 方法
            try {
                Method create = actorClass.getMethod("create");
                return (Behavior<M>) create.invoke(null);
            } catch (NoSuchMethodException e) {
                // 如果没有无参方法，再尝试有参方法
                Method create = actorClass.getMethod("create", String.class);
                return (Behavior<M>) create.invoke(null, context.getEntityId());
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("创建 Actor 失败：" + actorClass.getSimpleName(), e);
        }
    }


    /**
     * 获取当前节点的角色
     * 从 ActorSystem 运行时环境中读取，支持通过 JVM 参数动态配置
     * 例如：-Dakka.cluster.roles.0=gateway
     */
    public static Set<String> getCurrentNodeRoles(ActorSystem<?> system) {
        Set<String> roles = new HashSet<>();
        try {
            // 从 Akka Cluster 运行时获取角色（包含 JVM 参数覆盖后的配置）
            scala.collection.immutable.Set<String> scalaRoles =
                    akka.cluster.Cluster.get(system).selfRoles();

            scala.collection.Iterator<String> iterator = scalaRoles.iterator();
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
        return (EntityTypeKey<T>) ENTITY_TYPE_KEYS.computeIfAbsent(
                actorCmdClass,
                key -> EntityTypeKey.create(key, key.getSimpleName())
        );
    }
}
