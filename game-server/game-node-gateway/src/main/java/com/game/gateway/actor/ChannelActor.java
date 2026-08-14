package com.game.gateway.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.message.actor.S2C;
import com.game.message.rpc.GameRpcEnvelope;
import com.game.protocol.pb.SC_Logout;
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
                .onMessage(GameRpcEnvelope.class, this::logOut)
                .build();
    }

    private Behavior<S2C> logOut(GameRpcEnvelope envelope) {
        if (!(envelope.payload() instanceof SC_Logout)) {
            log.warn("Unhandled rpc payload: {}", envelope.payload().getClass().getName());
            return this;
        }
        log.info("ChannelActor logOut: {}", this.entityId);
        return this;
    }
}
