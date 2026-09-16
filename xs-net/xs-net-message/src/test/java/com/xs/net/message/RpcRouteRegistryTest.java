package com.xs.net.message;

import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;
import junit.framework.TestCase;

import java.util.List;

public class RpcRouteRegistryTest extends TestCase {
    public void testCreatesRouteMetadataFromRegistry() {
        RpcMessageSpec<NetMessage> spec = new RpcMessageSpec<>(
                1000, NetMessage.class, NetMessage.getDefaultInstance().getParserForType(), null);
        RpcMessageRegistry registry = new RpcMessageRegistry() {
            @Override
            public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
                return rpcNum == 1000 ? spec : null;
            }

            @Override
            public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
                return NetMessage.class.equals(messageClass) ? spec : null;
            }

            @Override
            public List<RpcMessageSpec<? extends MessageLite>> all() {
                return List.of(spec);
            }
        };

        assertEquals(1000, RpcRouteRegistry.from(registry).require(1000).spec().rpcNum());
    }
}
