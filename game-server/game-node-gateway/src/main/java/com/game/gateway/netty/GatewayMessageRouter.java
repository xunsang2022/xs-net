package com.game.gateway.netty;

import com.game.message.actor.logic.PlayerMessage;
import com.game.message.c2s.LoginRequest;
import com.game.message.c2s.LogoutRequest;
import com.game.message.c2s.PlayerEnterCommand;
import com.game.message.rpc.GameRpcMessages;
import com.google.protobuf.MessageLite;
import com.xs.core.akka.AkkaKit;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.RequestContext;
import com.xs.net.message.RpcRuntime;
import com.xs.net.transport.IngressAdmissionController;
import io.micrometer.core.instrument.Metrics;
import com.xs.net.message.pb.NetMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Routes a decoded client {@link NetMessage} into the existing PlayerActor path. */
@Slf4j
public final class GatewayMessageRouter {

    private final RpcRuntime runtime = new RpcRuntime(GameRpcMessages.INSTANCE);
    private final com.xs.net.message.RpcRouteRegistry routes =
            com.xs.net.message.RpcRouteRegistry.from(GameRpcMessages.INSTANCE);
    private final IngressAdmissionController admission = new IngressAdmissionController(4096);
    private final Map<String, IngressAdmissionController> connectionAdmissions = new ConcurrentHashMap<>();

    public void route(byte[] body) throws Exception {
        route(NetMessage.parseFrom(body));
    }

    public void route(byte[] body, String authenticatedChannelId) throws Exception {
        route(NetMessage.parseFrom(body), authenticatedChannelId);
    }

    public void route(NetMessage netMessage) throws Exception {
        route(netMessage, RequestContext.from(netMessage));
    }

    public void route(NetMessage netMessage, String authenticatedChannelId) throws Exception {
        route(netMessage, authenticatedContext(netMessage, authenticatedChannelId));
    }

    public void route(NetMessage netMessage, RequestContext incomingContext) throws Exception {
        String channelId = incomingContext == null ? null : incomingContext.channelId();
        IngressAdmissionController connectionAdmission = channelId == null || channelId.isBlank()
                ? null
                : connectionAdmissions.computeIfAbsent(channelId, ignored -> new IngressAdmissionController(256));
        boolean globalAcquired = admission.tryAcquire();
        boolean connectionAcquired = globalAcquired
                && (connectionAdmission == null || connectionAdmission.tryAcquire());
        if (!connectionAcquired) {
            if (globalAcquired) {
                admission.release();
            }
            Metrics.counter("gateway.ingress.rejected", "reason", "capacity").increment();
            log.warn("Gateway ingress rejected because admission capacity is full");
            return;
        }
        try {
            routeAccepted(netMessage, incomingContext);
        } finally {
            admission.release();
            if (connectionAdmission != null) {
                connectionAdmission.release();
                if (connectionAdmission.inFlight() == 0) {
                    connectionAdmissions.remove(channelId, connectionAdmission);
                }
            }
        }
    }

    static RequestContext authenticatedContext(NetMessage netMessage, String authenticatedChannelId) {
        RequestContext context = RequestContext.from(netMessage);
        if (authenticatedChannelId == null || authenticatedChannelId.isBlank()) {
            return context;
        }
        return context.withIdentity(context.playerId(), authenticatedChannelId);
    }

    private void routeAccepted(NetMessage netMessage, RequestContext incomingContext) throws Exception {
        com.xs.net.message.RpcRouteDescriptor route = routes.find(netMessage.getRpcNum());
        if (route == null) {
            log.warn("Unknown rpcNum: {}", netMessage.getRpcNum());
            return;
        }
        RpcMessageSpec<? extends MessageLite> spec = route.spec();
        MessageLite payload = spec.parser().parseFrom(netMessage.getData());
        Object actionMessage = spec.decode(payload);
        String playerId = route.playerId(actionMessage);
        if (playerId == null || playerId.isBlank()) {
            log.warn("Message without playerId, rpcNum={}", netMessage.getRpcNum());
            return;
        }
        RequestContext context = incomingContext == null
                ? RequestContext.root(playerId, null, java.time.Duration.ofSeconds(3))
                : incomingContext.withIdentity(playerId, incomingContext.channelId());
        if (context.expired()) {
            Metrics.counter("gateway.ingress.rejected", "reason", "expired").increment();
            log.warn("Gateway ingress rejected because request deadline expired: traceId={}", context.traceId());
            return;
        }
        if (actionMessage instanceof LoginRequest login) {
            String channelId = authenticatedChannelId(context, login.sessionId());
            ChannelRegistry.INSTANCE.recordLoginIngress(channelId, System.nanoTime());
            PlayerEnterCommand command = new PlayerEnterCommand(
                    Long.parseLong(login.playerId()), login.playerName(), channelId);
            AkkaKit.tell(GameRpcMessages.INSTANCE, PlayerMessage.class, command, playerId, context);
            return;
        }
        if (actionMessage instanceof LogoutRequest logout) {
            String channelId = authenticatedChannelId(context, logout.sessionId());
            LogoutRequest authenticatedLogout = new LogoutRequest(logout.playerId(), channelId);
            AkkaKit.tell(GameRpcMessages.INSTANCE, PlayerMessage.class, authenticatedLogout, playerId, context);
            return;
        }
        AkkaKit.tell(GameRpcMessages.INSTANCE, PlayerMessage.class, (PlayerMessage) actionMessage, playerId, context);
    }

    static String authenticatedChannelId(RequestContext context, String clientChannelId) {
        if (context != null && context.channelId() != null && !context.channelId().isBlank()) {
            return context.channelId();
        }
        return clientChannelId;
    }

    /** Compatibility entry point for authentication handlers during route migration. */
    static String extractPlayerId(Class<?> type, Object payload) {
        return com.xs.net.message.RouteValueExtractors.playerId().extract(payload);
    }

}
