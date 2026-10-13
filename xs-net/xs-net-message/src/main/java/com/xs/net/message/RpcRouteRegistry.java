package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RPC路由注册表，管理所有RPC路由描述符。
 * 提供按RPC编号查找路由的功能。
 */
public final class RpcRouteRegistry {
    /** 路由描述符列表 */
    private final List<RpcRouteDescriptor> routes;
    /** 按RPC编号索引的路由映射 */
    private final Map<Integer, RpcRouteDescriptor> byRpcNum;

    /**
     * 构造RPC路由注册表。
     *
     * @param routes 路由描述符列表
     * @throws IllegalArgumentException 如果存在重复的RPC编号
     */
    private RpcRouteRegistry(List<RpcRouteDescriptor> routes) {
        this.routes = List.copyOf(routes);
        Map<Integer, RpcRouteDescriptor> index = new LinkedHashMap<>();
        for (RpcRouteDescriptor route : routes) {
            RpcRouteDescriptor previous = index.putIfAbsent(route.spec().rpcNum(), route);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate RPC route: " + route.spec().rpcNum());
            }
        }
        this.byRpcNum = Map.copyOf(index);
    }

    /**
     * 从RPC消息注册表创建路由注册表。
     *
     * @param registry RPC消息注册表
     * @return 路由注册表
     */
    public static RpcRouteRegistry from(RpcMessageRegistry registry) {
        return new RpcRouteRegistry(registry.all().stream().map(RpcRouteDescriptor::from).toList());
    }

    /**
     * 根据RPC编号查找路由描述符。
     *
     * @param rpcNum RPC编号
     * @return 路由描述符，如果未找到返回null
     */
    public RpcRouteDescriptor find(int rpcNum) {
        return byRpcNum.get(rpcNum);
    }

    /**
     * 获取所有路由描述符。
     *
     * @return 路由描述符列表
     */
    public List<RpcRouteDescriptor> all() {
        return routes;
    }
}
