package com.game.gateway.actor;

import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.ClientEndpoint;
import com.game.message.actor.gateway.GatewayControlMessage;
import com.game.message.management.DisconnectChannelCommand;
import com.xs.core.actor.RpcActor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

@Slf4j
public final class GatewayControlActor extends RpcActor<GatewayControlMessage> {

    private final String entityId;

    private GatewayControlActor(ActorContext<GatewayControlMessage> context, String entityId) {
        super(context, entityId);
        this.entityId = entityId;
    }

    public static Behavior<GatewayControlMessage> create(String entityId) {
        return Behaviors.setup(context -> new GatewayControlActor(context, entityId));
    }

    @Override
    public Receive<GatewayControlMessage> createReceive() {
        return newRpcReceiveBuilder()
                .onMessage(DisconnectChannelCommand.class, this::onDisconnect)
                .build();
    }

    private Behavior<GatewayControlMessage> onDisconnect(DisconnectChannelCommand command) {
        ClientEndpoint endpoint = ChannelRegistry.INSTANCE.remove(entityId);
        if (endpoint == null) {
            log.info("GM disconnect skipped because channel is already absent: requestId={}, channelId={}",
                    command.requestId(), entityId);
            return this;
        }
        endpoint.close();
        log.info("GM disconnected channel: requestId={}, playerId={}, channelId={}",
                command.requestId(), command.playerId(), entityId);
        return Behaviors.stopped();
    }
}
