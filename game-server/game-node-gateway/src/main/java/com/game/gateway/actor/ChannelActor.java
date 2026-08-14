package com.game.gateway.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.message.action.SC_Logout;
import com.game.message.actor.S2C;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class ChannelActor extends AbstractBehavior<S2C> {
    private final String entityId;

    public ChannelActor(ActorContext<S2C> context, String entityId) {
        super(context);
        this.entityId = entityId;

        log.info("ChannelActor created: {}", this.entityId);
    }

    public static Behavior<S2C> create(String entityId) {
        return Behaviors.setup(context -> new ChannelActor(context, entityId));
    }

    @Override
    public Receive<S2C> createReceive() {
        return newReceiveBuilder()
                .onMessage(SC_Logout.class, this::logOut)
                .build();
    }

    private Behavior<S2C> logOut(SC_Logout logout) {
        log.info("ChannelActor logOut: {}", this.entityId);
        return this;
    }
}
