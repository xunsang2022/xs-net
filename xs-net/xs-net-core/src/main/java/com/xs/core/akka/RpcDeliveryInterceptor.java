package com.xs.core.akka;

import com.xs.net.message.RequestContext;
import com.xs.net.message.RpcMessageRegistry;

/**
 * Optional fast path for a role that can deliver messages without entering its
 * normal sharded entity mailbox. Returning {@code false} delegates to the
 * regular Cluster Sharding route.
 */
@FunctionalInterface
public interface RpcDeliveryInterceptor {

    boolean intercept(
            RpcMessageRegistry registry,
            Class<?> roleClass,
            Object message,
            String entityId,
            RequestContext context
    );
}
