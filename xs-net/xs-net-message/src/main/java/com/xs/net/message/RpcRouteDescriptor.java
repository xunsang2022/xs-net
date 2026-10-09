package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * RPC路由描述符，定义单个RPC消息的路由配置。
 * 包含消息规范、传递类型、支持的传输协议和路由值提取器。
 *
 * @param spec RPC消息规范
 * @param deliveryClass 消息传递类型
 * @param transports 支持的传输协议集合
 * @param playerIdExtractor 玩家ID提取器
 * @param channelIdExtractor 频道ID提取器
 */
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

    /**
     * 从RPC消息规范创建路由描述符。
     *
     * @param spec RPC消息规范
     * @return 路由描述符
     */
    public static RpcRouteDescriptor from(RpcMessageSpec<? extends MessageLite> spec) {
        RpcDeliveryClass delivery = switch (spec.direction()) {
            case INTERNAL -> RpcDeliveryClass.STATE_LATEST;
            case HTTP_RESPONSE -> RpcDeliveryClass.REALTIME;
        };
        RouteValueExtractor player = spec.playerRoutingRequired() ? RouteValueExtractors.playerId() : null;
        RouteValueExtractor channel = spec.channelRoutingRequired() ? RouteValueExtractors.channelId() : null;
        return new RpcRouteDescriptor(spec, delivery, EnumSet.allOf(TransportKind.class), player, channel);
    }

    /**
     * 从消息对象中提取玩家ID。
     *
     * @param message 消息对象
     * @return 玩家ID，如果不需要路由返回null
     */
    public String playerId(Object message) {
        return playerIdExtractor == null ? null : playerIdExtractor.extract(message);
    }

    /**
     * 从消息对象中提取频道ID。
     *
     * @param message 消息对象
     * @return 频道ID，如果不需要路由返回null
     */
    public String channelId(Object message) {
        return channelIdExtractor == null ? null : channelIdExtractor.extract(message);
    }

    /**
     * 获取RPC编号。
     *
     * @return RPC编号
     */
    public int rpcNumber() {
        return spec.rpcNum();
    }

    /**
     * 获取消息方向。
     *
     * @return 消息方向
     */
    public RpcMessageDirection direction() {
        return spec.direction();
    }

    /**
     * 获取目标角色类型。
     *
     * @return 角色类型
     */
    public Class<?> targetRole() {
        return spec.roleClass();
    }

    /**
     * 检查消息是否对外可见。
     *
     * @return 如果对外可见返回true，否则返回false
     */
    public boolean publicVisible() {
        return spec.publicVisible();
    }

    /**
     * 检查是否需要玩家路由。
     *
     * @return 如果需要返回true，否则返回false
     */
    public boolean playerRoutingRequired() {
        return spec.playerRoutingRequired();
    }

    /**
     * 检查是否需要频道路由。
     *
     * @return 如果需要返回true，否则返回false
     */
    public boolean channelRoutingRequired() {
        return spec.channelRoutingRequired();
    }

    /**
     * 获取请求DTO类型。
     *
     * @return 请求DTO类
     */
    public Class<?> requestType() {
        return spec.dtoClass();
    }

    public Class<?> responseType() {
        return spec.handler() == null ? null : spec.handler().responseType();
    }
}
