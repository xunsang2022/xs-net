package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public record RpcRouteDescriptor(
        RpcMessageSpec<? extends MessageLite> spec,
        RpcDeliveryClass deliveryClass,
        Set<TransportKind> transports,
        RouteValueExtractor playerIdExtractor,
        RouteValueExtractor channelIdExtractor
) {
    public RpcRouteDescriptor {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(deliveryClass, "deliveryClass");
        transports = transports == null || transports.isEmpty()
                ? Set.of(TransportKind.TCP, TransportKind.UDP, TransportKind.WEBSOCKET)
                : Set.copyOf(transports);
    }

    public static RpcRouteDescriptor from(RpcMessageSpec<? extends MessageLite> spec) {
        RpcDeliveryClass delivery = switch (spec.direction()) {
            case RESPONSE -> RpcDeliveryClass.RESPONSE;
            case INTERNAL -> RpcDeliveryClass.STATE_LATEST;
            default -> RpcDeliveryClass.REALTIME;
        };
        RouteValueExtractor player = spec.playerRoutingRequired() ? RouteValueExtractors.playerId() : null;
        RouteValueExtractor channel = spec.channelRoutingRequired() ? RouteValueExtractors.channelId() : null;
        return new RpcRouteDescriptor(spec, delivery, EnumSet.allOf(TransportKind.class), player, channel);
    }

    public String playerId(Object message) {
        return playerIdExtractor == null ? null : playerIdExtractor.extract(message);
    }

    public String channelId(Object message) {
        return channelIdExtractor == null ? null : channelIdExtractor.extract(message);
    }

    public int rpcNumber() {
        return spec.rpcNum();
    }

    public RpcMessageDirection direction() {
        return spec.direction();
    }

    public Class<?> targetRole() {
        return spec.roleClass();
    }

    public boolean publicVisible() {
        return spec.publicVisible();
    }

    public boolean playerRoutingRequired() {
        return spec.playerRoutingRequired();
    }

    public boolean channelRoutingRequired() {
        return spec.channelRoutingRequired();
    }

    public Class<?> requestType() {
        return spec.dtoClass();
    }

    public Class<?> responseType() {
        return spec.handler() == null ? null : spec.handler().responseType();
    }
}
