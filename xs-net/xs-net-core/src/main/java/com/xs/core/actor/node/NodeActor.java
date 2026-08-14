package com.xs.core.actor.node;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.core.config.GameFrameworkProperties;
import com.xs.core.spring.ClassScanner;
import com.xs.core.spring.ClassUtil;
import com.xs.core.spring.SpringUtil;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcEnvelopeFactory;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import lombok.SneakyThrows;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Set;

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

        ActorSystem<?> system = context.getSystem();
        ClusterSharding sharding = ClusterSharding.get(system);
        AkkaKit.setClusterSharding(sharding);
        Set<String> roles = AkkaKit.getCurrentNodeRoles(system);
        roles.remove("dc-default");
        log.info("当前节点角色：{}", roles);

        RpcMessageRegistry messageRegistry = null;
        if (!MessageAble.isInitialized()) {
            try {
                messageRegistry = SpringUtil.getBean(RpcMessageRegistry.class);
                RpcEnvelopeFactory envelopeFactory = SpringUtil.getBean(RpcEnvelopeFactory.class);
                MessageAble.initialize(messageRegistry, envelopeFactory);
            } catch (Exception e) {
                log.error("Failed to initialize RpcMessageRegistry from Spring", e);
            }
        }
        if (messageRegistry == null) {
            messageRegistry = MessageAble.registry();
        }

        this.registerALocalActor(sharding, basePackages, roles);
        this.registeredAgentActor(sharding,
                messageRegistry == null ? List.of() : messageRegistry.all(), roles);
    }

    private void registeredAgentActor(
            ClusterSharding sharding,
            List<RpcMessageSpec<? extends MessageLite>> specs,
            Set<String> roles
    ) {
        specs.stream()
                .map(RpcMessageSpec::roleClass)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(roleClass -> {
                    Roles annotation = roleClass.getAnnotation(Roles.class);
                    if (annotation == null) {
                        return;
                    }
                    String messageRoles = annotation.value();
                    if (!roles.contains(messageRoles)) {
                        AkkaKit.entityRefForClusterSharding(sharding, messageRoles, roleClass);
                    }
                });

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


