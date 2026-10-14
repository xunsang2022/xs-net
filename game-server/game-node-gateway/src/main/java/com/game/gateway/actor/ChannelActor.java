package com.game.gateway.actor;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import com.game.gateway.netty.ChannelRegistry;
import com.game.gateway.netty.ClientEndpoint;
import com.game.message.actor.S2C;
import com.xs.kernel.actor.RpcActor;
import com.xs.net.message.MessageAble;
import com.xs.net.message.RequestContext;
import com.xs.net.message.pb.NetMessage;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 客户端通道 Actor（Akka sharding，entityId = channelId）。
 * 收到 Logic 下发的 S2C 消息后，编码为 NetMessage 写回客户端端点（TCP Channel 或 UDP 会话）。
 */
@Slf4j
public class ChannelActor extends RpcActor<S2C> {

    private static final String METRIC_S2C_MESSAGES = "gateway.s2c.messages";
    private static final String METRIC_S2C_DROPPED = "gateway.s2c.dropped";

    /** S2C 从进入 ChannelActor 到写回端点的处理耗时（含信封构建），用于观测网关内下行时延。 */
    private static final Timer S2C_WRITE_DURATION = Metrics.timer("gateway.s2c.write.duration");

    private final String entityId;

    public ChannelActor(ActorContext<S2C> context, String entityId) {
        super(context, entityId);
        this.entityId = entityId;
    }

    public static Behavior<S2C> create(String entityId) {
        return Behaviors.setup(context -> new ChannelActor(context, entityId));
    }

    @Override
    public Receive<S2C> createReceive() {
        return newRpcReceiveBuilder()
                .onAnyMessage(message -> contextual(message, this::onS2C))
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
            long startNanos = System.nanoTime();
            NetMessage netMessage = MessageAble.toNetMessage(message);
            RequestContext context = currentRequestContext();
            if (context != null) {
                netMessage = context.applyTo(netMessage.toBuilder()).build();
            }
            endpoint.write(netMessage);
            S2C_WRITE_DURATION.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            Long ingressNanos = ChannelRegistry.INSTANCE.takeLoginIngress(entityId);
            if (ingressNanos != null) {
                Metrics.timer("gateway.login.roundtrip", "transport", "tcp-udp")
                        .record(System.nanoTime() - ingressNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            Metrics.counter(METRIC_S2C_MESSAGES).increment();
        } catch (Exception e) {
            log.error("Failed to write S2C to endpoint: channelId={}", entityId, e);
        }
        return this;
    }
}
