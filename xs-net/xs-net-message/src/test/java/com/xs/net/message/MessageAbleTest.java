package com.xs.net.message;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.serializer.NetMessageSerializer;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

public class MessageAbleTest extends TestCase {

    private static final int TEST_RPC = 1000;

    @Override
    protected void setUp() {
        RpcMessageSpec<NetMessage> spec = new RpcMessageSpec<>(
                TEST_RPC,
                NetMessage.class,
                NetMessage.getDefaultInstance().getParserForType(),
                null
        );
        MessageAble.initialize(new RpcMessageRegistry() {
            @Override
            public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
                return rpcNum == TEST_RPC ? spec : null;
            }

            @Override
            public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
                return NetMessage.class.equals(messageClass) ? spec : null;
            }

            @Override
            public List<RpcMessageSpec<? extends MessageLite>> all() {
                return List.of(spec);
            }
        }, TestRpcMessage::new);
    }

    public void testFrameRoundTrip() throws Exception {
        NetMessage business = NetMessage.newBuilder()
                .setRpcNum(2000)
                .setData(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();
        RpcMessage rpc = new TestRpcMessage(TEST_RPC, business);

        NetMessage wrapped = MessageAble.toNetMessage(rpc);
        byte[] frame = MessageAble.toNetByteMsg(wrapped);
        NetMessage parsed = MessageAble.toNetMessage(frame);

        assertEquals(TEST_RPC, parsed.getRpcNum());
        assertTrue(Arrays.equals(business.toByteArray(), parsed.getData().toByteArray()));
    }

    public void testActionMessageRoundTrip() throws Exception {
        NetMessage business = NetMessage.newBuilder()
                .setRpcNum(2000)
                .setData(ByteString.copyFrom(new byte[]{4, 5, 6}))
                .build();
        NetMessage envelope = MessageAble.toNetMessage(new TestRpcMessage(TEST_RPC, business));

        Object action = MessageAble.toActionMessage(envelope);
        assertTrue(action instanceof RpcMessage);
        RpcMessage rpc = (RpcMessage) action;
        assertEquals(TEST_RPC, rpc.rpcNum());
        assertEquals(business, rpc.payload());
    }

    public void testExplicitRegistryOverridesGlobalRegistry() throws Exception {
        int explicitRpc = TEST_RPC + 1;
        RpcMessageSpec<NetMessage> explicitSpec = new RpcMessageSpec<>(
                explicitRpc,
                NetMessage.class,
                NetMessage.getDefaultInstance().getParserForType(),
                TestActionMessage.class,
                null,
                RpcMessageDirection.INTERNAL,
                false,
                false,
                false,
                new RpcPayloadCodec<TestActionMessage, NetMessage>() {
                    @Override
                    public NetMessage encode(TestActionMessage dto) {
                        return dto.payload();
                    }

                    @Override
                    public TestActionMessage decode(NetMessage payload) {
                        return new TestActionMessage(payload);
                    }
                },
                null
        );
        RpcMessageRegistry explicitRegistry = registryFor(explicitSpec);
        NetMessage payload = NetMessage.newBuilder()
                .setRpcNum(2000)
                .setData(ByteString.copyFrom(new byte[]{9, 8, 7}))
                .build();
        NetMessage envelope = NetMessage.newBuilder()
                .setRpcNum(explicitRpc)
                .setData(ByteString.copyFrom(payload.toByteArray()))
                .build();

        Object decoded = MessageAble.toActionMessage(envelope, explicitRegistry);
        assertTrue(decoded instanceof TestActionMessage);

        NetMessage encoded = MessageAble.toNetMessage(decoded, explicitRegistry);
        assertEquals(explicitRpc, encoded.getRpcNum());
        assertEquals(payload, NetMessage.parseFrom(encoded.getData()));
    }

    public void testUnknownRpcIsRejected() {
        try {
            MessageAble.toActionMessage(NetMessage.newBuilder().setRpcNum(9999).build(), MessageAble.registry());
            fail("unknown rpc must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unknown rpc number"));
        } catch (InvalidProtocolBufferException unexpected) {
            fail("unknown rpc should fail before payload parsing");
        }
    }

    public void testMalformedPayloadIsRejected() {
        NetMessage malformed = NetMessage.newBuilder()
                .setRpcNum(TEST_RPC)
                .setData(ByteString.copyFrom(new byte[]{0x08}))
                .build();
        try {
            MessageAble.toActionMessage(malformed, MessageAble.registry());
            fail("malformed payload must be rejected");
        } catch (InvalidProtocolBufferException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    public void testSerializerRoundTrip() throws Exception {
        NetMessage business = NetMessage.newBuilder()
                .setRpcNum(2000)
                .setData(ByteString.copyFrom(new byte[]{7, 8, 9}))
                .build();
        NetMessage envelope = MessageAble.toNetMessage(new TestRpcMessage(TEST_RPC, business));
        NetMessageSerializer serializer = new NetMessageSerializer();

        Object decoded = serializer.fromBinary(serializer.toBinary(envelope));
        assertTrue(decoded instanceof NetMessage);
        assertEquals(envelope, decoded);
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

    private record TestActionMessage(NetMessage payload) {
    }

    private static RpcMessageRegistry registryFor(RpcMessageSpec<NetMessage> spec) {
        return new RpcMessageRegistry() {
            @Override
            public RpcMessageSpec<? extends MessageLite> find(int rpcNum) {
                return rpcNum == spec.rpcNum() ? spec : null;
            }

            @Override
            public RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass) {
                return spec.messageClass().equals(messageClass) ? spec : null;
            }

            @Override
            public List<RpcMessageSpec<? extends MessageLite>> all() {
                return List.of(spec);
            }
        };
    }
}
