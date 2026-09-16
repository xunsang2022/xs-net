package com.xs.net.transport;

import com.xs.net.message.RpcDeliveryClass;
import com.xs.net.message.RequestContext;
import com.xs.net.message.pb.NetMessage;

public record OutboundMessage(NetMessage message, RequestContext context, RpcDeliveryClass deliveryClass) {
}
