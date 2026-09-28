package com.xs.kernel.akka;

import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessage;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.pb.NetMessage;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AkkaKitRpcRoutingTest {

    private static final int RPC_NUMBER = 7000;
    private static final RpcMessageSpec<NetMessage> SPEC = new RpcMessageSpec<>(
            RPC_NUMBER,
            NetMessage.class,
            NetMessage.getDefaultInstance().getParserForType(),
            TestRole.class
    );
    private static final RpcMessageRegistry REGISTRY = new RpcMessageRegistry() {
        @Override
        public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
            return rpcNum == RPC_NUMBER ? SPEC : null;
        }

        @Override
        public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
            return NetMessage.class.equals(messageClass) ? SPEC : null;
        }

        @Override
        public List<RpcMessageSpec<? extends MessageLite>> all() {
            return List.of(SPEC);
        }
    };

    @After
    public void resetClusterSharding() {
        AkkaKit.setClusterSharding(null);
    }

    @Test
    public void validRpcIsEncodedAndDelivered() {
        ClusterSharding sharding = mock(ClusterSharding.class);
        EntityRef<NetMessage> entityRef = mock(EntityRef.class);
        doReturn(entityRef).when(sharding).entityRefFor(any(EntityTypeKey.class), eq("entity-1"));
        AkkaKit.setClusterSharding(sharding);

        NetMessage payload = NetMessage.newBuilder().setRpcNum(123).build();
        AkkaKit.tell(REGISTRY, TestRole.class, new TestRpcMessage(RPC_NUMBER, payload), "entity-1");

        verify(entityRef).tell(any());
    }

    @Test
    public void roleMismatchIsRejectedBeforeEntityLookup() {
        AkkaKit.setClusterSharding(null);

        try {
            AkkaKit.tell(REGISTRY, WrongRole.class,
                    new TestRpcMessage(RPC_NUMBER, NetMessage.getDefaultInstance()), "entity-1");
            fail("role mismatch must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("belongs to role"));
        }
    }

    @Test
    public void unknownRpcIsRejectedBeforeEntityLookup() {
        AkkaKit.setClusterSharding(null);

        try {
            AkkaKit.tell(REGISTRY, TestRole.class,
                    new TestRpcMessage(RPC_NUMBER + 1, NetMessage.getDefaultInstance()), "entity-1");
            fail("unknown rpc must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("No RPC definition"));
        }
    }

    @Test
    public void nullRpcPayloadIsRejected() {
        AkkaKit.setClusterSharding(null);

        try {
            AkkaKit.tell(REGISTRY, TestRole.class, new TestRpcMessage(RPC_NUMBER, null), "entity-1");
            fail("null payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("payload type"));
        }
    }

    private interface TestRole {
    }

    private interface WrongRole {
    }

    private static final class TestRpcMessage implements RpcMessage {
        private final int rpcNum;
        private final MessageLite payload;

        private TestRpcMessage(int rpcNum, MessageLite payload) {
            this.rpcNum = rpcNum;
            this.payload = payload;
        }

        @Override
        public int rpcNum() {
            return rpcNum;
        }

        @Override
        public MessageLite payload() {
            return payload;
        }
    }
}
