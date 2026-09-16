package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RpcRouteRegistry {
    private final List<RpcRouteDescriptor> routes;
    private final Map<Integer, RpcRouteDescriptor> byRpcNum;

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

    public static RpcRouteRegistry from(RpcMessageRegistry registry) {
        return new RpcRouteRegistry(registry.all().stream().map(RpcRouteDescriptor::from).toList());
    }

    public RpcRouteDescriptor find(int rpcNum) {
        return byRpcNum.get(rpcNum);
    }

    public List<RpcRouteDescriptor> all() {
        return routes;
    }

    public RpcRouteDescriptor require(int rpcNum) {
        RpcRouteDescriptor route = find(rpcNum);
        if (route == null) {
            throw new IllegalArgumentException("Unknown RPC route: " + rpcNum);
        }
        return route;
    }
}
