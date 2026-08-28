package com.game.gateway.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.ClientEndpoint;
import com.game.message.actor.S2C;
import com.xs.net.message.MessageAble;
import com.xs.net.message.pb.NetMessage;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端通道 Actor（Akka sharding，entityId = channelId）。
 * 收到 Logic 下发的 S2C 消息后，编码为 NetMessage 写回客户端端点（TCP Channel 或 UDP 会话）。
 */
@Slf4j
public class ChannelActor extends AbstractBehavior<S2C> {

    private static final String METRIC_S2C_MESSAGES = "gateway.s2c.messages";
    private static final String METRIC_S2C_DROPPED = "gateway.s2c.dropped";

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
                .onAnyMessage(this::onS2C)
                .build();
    }

    private Behavior<S2C> onS2C(S2C message) {
        ClientEndpoint endpoint = ChannelRegistry.INSTANCE.get(entityId);
        if (endpoint == null || !endpoint.isActive()) {
            log.warn("No active endpoint for S2C: channelId={}, payload={}",
                    entityId, message.getClass().getSimpleName());
            Metrics.counter(METRIC_S2C_DROPPED).increment();
            return this;
        }
        try {
            NetMessage netMessage = MessageAble.toNetMessage(message);
            endpoint.write(netMessage);
            Metrics.counter(METRIC_S2C_MESSAGES).increment();
        } catch (Exception e) {
            log.error("Failed to write S2C to endpoint: channelId={}", entityId, e);
        }
        return this;
    }
}
