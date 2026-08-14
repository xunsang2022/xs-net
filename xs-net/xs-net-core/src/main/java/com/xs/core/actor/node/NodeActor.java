package com.xs.core.actor.node;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import com.xs.core.akka.AkkaKit;
import com.xs.core.config.GameFrameworkProperties;
import com.xs.core.spring.ClassScanner;
import com.xs.core.spring.ClassUtil;
import com.xs.net.message.MessageAble;
import com.xs.net.message.annotation.ActionMessage;
import lombok.SneakyThrows;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class NodeActor extends AbstractBehavior<Command> {
    private final Logger log = this.getContext().getLog();
    private final GameFrameworkProperties properties;

    public NodeActor(ActorContext<Command> context, GameFrameworkProperties properties) {
        super(context);
        this.properties = properties;
        this.init(context);
    }

    @SneakyThrows
    private void init(ActorContext<Command> context) {
        String[] basePackages = properties.getMessageBasePackages();

        // 使用 ClassScanner 扫描带有 @ActionMessage 注解的类
        Set<Class<?>> messageClasses = ClassScanner.scanClassesWithAnnotation(basePackages, ActionMessage.class);

        ActorSystem<?> system = context.getSystem();
        ClusterSharding sharding = ClusterSharding.get(system);
        Set<String> roles = AkkaKit.getCurrentNodeRoles(system);
        roles.remove("dc-default");
        log.info("当前节点角色：{}", roles);

        this.registrationMessageSerialization(messageClasses);
        this.registerALocalActor(sharding, basePackages, roles);
        this.registeredAgentActor(sharding, messageClasses, roles);
    }

    private void registeredAgentActor(ClusterSharding sharding, Set<Class<?>> messageClasses, Set<String> roles) {
        messageClasses.stream()
                .map(ClassUtil::findMessageInterface)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())
                .forEach(messageClass -> {
                    Roles annotation = messageClass.getAnnotation(Roles.class);
                    if (annotation == null) {
                        return;
                    }
                    String messageRoles = annotation.value();
                    if (!roles.contains(messageRoles)) {
                        AkkaKit.entityRefForClusterSharding(sharding, messageRoles, messageClass);
                    }
                });

    }

    private void registrationMessageSerialization(Set<Class<?>> messageClasses) {
        for (Class<?> messageClass : messageClasses) {
            int value = messageClass.getAnnotation(ActionMessage.class).value();
            MessageAble.rpcMap.put(value, messageClass);
            log.info("{}{}",value , messageClass.getSimpleName());
        }
    }

    private void registerALocalActor(ClusterSharding sharding, String[] basePackages, Set<String> roles) {
        Set<Class<?>> locationActorClasses = ClassScanner.scanClasses(
                basePackages,
                aClass -> aClass.getSuperclass() == AbstractBehavior.class && aClass != NodeActor.class
        );
        for (Class<?> locationActorClass : locationActorClasses) {
            Class<?> messageInterface = ClassUtil.findMessageInterface(locationActorClass);
            if (messageInterface == null) continue;
            Roles annotation = messageInterface.getAnnotation(Roles.class);
            if (annotation == null) continue;
            String actorRole = annotation.value();
            if (!roles.contains(actorRole)) continue;
            AkkaKit.initClusterSharding(sharding, messageInterface, locationActorClass, actorRole);
        }
    }


    public static Behavior<Command> create(GameFrameworkProperties properties) {
        return Behaviors.setup(
                context -> new NodeActor(context, properties)
        );
    }


    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().build();
    }
}


