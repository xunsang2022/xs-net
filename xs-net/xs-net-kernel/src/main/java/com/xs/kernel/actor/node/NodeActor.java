package com.xs.kernel.actor.node;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import com.google.protobuf.MessageLite;
import com.xs.kernel.actor.RpcActor;
import com.xs.kernel.akka.AkkaKit;
import com.xs.kernel.config.GameFrameworkProperties;
import com.xs.kernel.spring.ClassScanner;
import com.xs.kernel.spring.ClassUtil;
import com.xs.kernel.spring.SpringUtil;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import lombok.SneakyThrows;
import org.slf4j.Logger;

import java.lang.reflect.Modifier;
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
        String[] basePackages = properties.getActorBasePackages();
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
                MessageAble.initialize(messageRegistry);
            } catch (Exception e) {
                log.error("Failed to initialize RpcMessageRegistry from Spring", e);
            }
        }
        if (messageRegistry == null) {
            messageRegistry = MessageAble.registry();
        }
        registerALocalActor(sharding, basePackages, roles);
        registeredAgentActor(sharding, messageRegistry == null ? List.of() : messageRegistry.all(), roles);
    }

    private void registeredAgentActor(
            ClusterSharding sharding,
            List<RpcMessageSpec<? extends MessageLite>> specs,
            Set<String> roles
    ) {
        specs.stream().map(RpcMessageSpec::roleClass).filter(Objects::nonNull).distinct().forEach(roleClass -> {
            Roles annotation = roleClass.getAnnotation(Roles.class);
            if (annotation != null && !roles.contains(annotation.value())) {
                AkkaKit.entityRefForClusterSharding(sharding, annotation.value(), roleClass);
            }
        });
    }

    private void registerALocalActor(ClusterSharding sharding, String[] basePackages, Set<String> roles) {
        Set<Class<?>> actorClasses = ClassScanner.scanClasses(basePackages, this::isConcreteActor);
        for (Class<?> actorClass : actorClasses) {
            Class<?> messageInterface = ClassUtil.findMessageInterface(actorClass);
            if (messageInterface == null) {
                continue;
            }
            Roles annotation = messageInterface.getAnnotation(Roles.class);
            if (annotation != null && roles.contains(annotation.value())) {
                ActorDispatcher dispatcher = actorClass.getAnnotation(ActorDispatcher.class);
                AkkaKit.initClusterSharding(
                        sharding,
                        messageInterface,
                        actorClass,
                        annotation.value(),
                        dispatcher == null ? AkkaKit.ENTITY_DISPATCHER : dispatcher.value()
                );
            }
        }
    }

    private boolean isConcreteActor(Class<?> actorClass) {
        return AbstractBehavior.class.isAssignableFrom(actorClass)
                && actorClass != NodeActor.class
                && actorClass != RpcActor.class
                && !actorClass.isInterface()
                && !Modifier.isAbstract(actorClass.getModifiers());
    }

    public static Behavior<Command> create(GameFrameworkProperties properties) {
        return Behaviors.setup(context -> new NodeActor(context, properties));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder().build();
    }
}
