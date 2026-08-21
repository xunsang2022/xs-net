package com.game.gateway.actor;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.NettyFramer;
import com.game.message.actor.S2C;
import com.game.message.rpc.GameRpcEnvelope;
import com.xs.net.message.pb.NetMessage;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

/**
 * 客户端通道 Actor（Akka sharding，entityId = channelId）。
 * 收到 Logic 下发的 S2C 消息后，编码为 NetMessage 写回客户端 Netty Channel。
 */
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
                .onMessage(GameRpcEnvelope.class, this::onS2C)
                .build();
    }

    private Behavior<S2C> onS2C(GameRpcEnvelope envelope) {
        Channel channel = ChannelRegistry.INSTANCE.get(entityId);
        if (channel == null || !channel.isActive()) {
            log.warn("No active channel for S2C: channelId={}, payload={}",
                    entityId, envelope.payload().getClass().getSimpleName());
            return this;
        }
        try {
            NetMessage netMessage = NetMessage.newBuilder()
                    .setRpcNum(envelope.rpcNum())
                    .setData(envelope.payload().toByteString())
                    .build();
            channel.writeAndFlush(NettyFramer.encode(netMessage));
        } catch (Exception e) {
            log.error("Failed to write S2C to channel: channelId={}", entityId, e);
        }
        return this;
    }
}