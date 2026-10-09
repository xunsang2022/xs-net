package com.xs.net.message;

import com.xs.net.message.pb.NetMessage;
import junit.framework.TestCase;

public class RpcMessageRegistryBuilderTest extends TestCase {

    public void testBuildsImmutableRegistryAndUsesCodec() {
        RpcPayloadCodec<String, NetMessage> codec = new RpcPayloadCodec<>() {
            @Override
            public NetMessage encode(String dto) {
                return NetMessage.newBuilder().setUserIp(dto).build();
            }

            @Override
            public String decode(NetMessage payload) {
                return payload.getUserIp();
            }
        };
        RpcMessageSpec<NetMessage> spec = new RpcMessageSpec<>(
                1000,
                NetMessage.class,
                NetMessage.getDefaultInstance().getParserForType(),
                String.class,
                TestRole.class,
                RpcMessageDirection.INTERNAL,
                true,
                true,
                false,
                codec,
                new RpcHandlerMetadata(TestActor.class.getName(), "handle", String.class, null)
        );

        RpcMessageRegistry registry = new RpcMessageRegistryBuilder().register(spec).build();

        assertSame(spec, registry.find(1000));
        assertSame(spec, registry.findByClass(NetMessage.class));
        assertSame(spec, registry.findByDtoClass(String.class));
        assertEquals(1, registry.findByRole(TestRole.class).size());
        assertEquals("127.0.0.1", spec.decode(NetMessage.newBuilder().setUserIp("127.0.0.1").build()));
        assertEquals("127.0.0.1", spec.encode("127.0.0.1").getUserIp());
        try {
            assertEquals(null, spec.handler().invoke(new TestActor(), "127.0.0.1"));
        } catch (Exception exception) {
            fail(exception.getMessage());
        }
        try {
            registry.all().add(spec);
            fail("registry must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }

    public void testRejectsDuplicateRpcNumber() {
        RpcMessageRegistryBuilder builder = new RpcMessageRegistryBuilder();
        builder.register(simpleSpec(1000));
        try {
            builder.register(simpleSpec(1000));
            fail("duplicate rpc number must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rpc number"));
        }
    }

    public void testInvokesHandlerOnActorComponent() {
        RpcHandlerMetadata metadata = new RpcHandlerMetadata(
                ComponentActor.class.getName(), "component", "handle", String.class, String.class);
        try {
            assertEquals("handled: request", metadata.invoke(new ComponentActor(), "request"));
        } catch (Exception exception) {
            fail(exception.getMessage());
        }
    }

    public void testRejectsDuplicateProtobufClass() {
        RpcMessageRegistryBuilder builder = new RpcMessageRegistryBuilder();
        builder.register(simpleSpec(1000));
        try {
            builder.register(simpleSpec(1001));
            fail("duplicate protobuf class must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("protobuf class"));
        }
    }

    private static RpcMessageSpec<NetMessage> simpleSpec(int rpcNum) {
        return new RpcMessageSpec<>(rpcNum, NetMessage.class,
                NetMessage.getDefaultInstance().getParserForType(), null);
    }

    private interface TestRole {
    }

    private static final class TestActor {
        private void handle(String request) {
        }
    }

    private static final class ComponentActor {
        private final Component component = new Component();
    }

    private static final class Component {
        private String handle(String request) {
            return "handled: " + request;
        }
    }
}
