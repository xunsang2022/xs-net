package com.xs.core.actor;

import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcMessageRegistry;
import com.xs.net.message.RpcMessageSpec;
import com.xs.net.message.RpcPayloadCodec;
import com.xs.net.message.pb.NetMessage;
import org.apache.pekko.actor.testkit.typed.javadsl.BehaviorTestKit;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class RpcActorTest {

    private static final int RPC_NUMBER = 7100;

    @Test
    public void explicitRegistryDecodesEnvelopeAndDispatchesToHandler() {
        RpcMessageRegistry registry = registryFor(RPC_NUMBER);
        List<TestCommand> received = new ArrayList<>();
        BehaviorTestKit<TestMessage> testKit = BehaviorTestKit.create(
                RecordingActor.create(registry, received));

        runEnvelope(testKit, envelope(RPC_NUMBER, "valid"));
        testKit.runOne();

        assertEquals(List.of(new TestCommand("valid")), received);
    }

    @Test
    public void invalidEnvelopeIsDroppedAndActorContinues() {
        RpcMessageRegistry registry = registryFor(RPC_NUMBER);
        List<TestCommand> received = new ArrayList<>();
        BehaviorTestKit<TestMessage> testKit = BehaviorTestKit.create(
                RecordingActor.create(registry, received));

        runEnvelope(testKit, envelope(RPC_NUMBER + 1, "unknown"));
        runEnvelope(testKit, envelope(RPC_NUMBER, "valid-after-invalid"));
        testKit.runOne();

        assertEquals(List.of(new TestCommand("valid-after-invalid")), received);
    }

    @Test
    public void actorUsesCapturedRegistryAfterGlobalRegistryChanges() {
        RpcMessageRegistry actorRegistry = registryFor(RPC_NUMBER);
        RpcMessageRegistry otherRegistry = registryFor(RPC_NUMBER + 1);
        List<TestCommand> received = new ArrayList<>();
        BehaviorTestKit<TestMessage> testKit = BehaviorTestKit.create(
                RecordingActor.create(actorRegistry, received));

        com.xs.net.message.RpcMessageRegistry previous = com.xs.net.message.MessageAble.registry();
        try {
            com.xs.net.message.MessageAble.initialize(otherRegistry);
            runEnvelope(testKit, envelope(RPC_NUMBER, "captured"));
            testKit.runOne();
        } finally {
            com.xs.net.message.MessageAble.initialize(previous);
        }

        assertEquals(List.of(new TestCommand("captured")), received);
    }

    private static NetMessage envelope(int rpcNum, String value) {
        return NetMessage.newBuilder()
                .setRpcNum(rpcNum)
                .setData(NetMessage.newBuilder().setUserIp(value).build().toByteString())
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void runEnvelope(BehaviorTestKit<TestMessage> testKit, NetMessage envelope) {
        ((BehaviorTestKit) testKit).run(envelope);
    }

    private static RpcMessageRegistry registryFor(int rpcNum) {
        RpcMessageSpec<NetMessage> spec = new RpcMessageSpec<>(
                rpcNum,
                NetMessage.class,
                NetMessage.getDefaultInstance().getParserForType(),
                TestCommand.class,
                TestRole.class,
                com.xs.net.message.RpcMessageDirection.INTERNAL,
                false,
                false,
                false,
                new RpcPayloadCodec<TestCommand, NetMessage>() {
                    @Override
                    public NetMessage encode(TestCommand dto) {
                        return NetMessage.newBuilder().setUserIp(dto.value()).build();
                    }

                    @Override
                    public TestCommand decode(NetMessage payload) {
                        return new TestCommand(payload.getUserIp());
                    }
                },
                null
        );
        return new RpcMessageRegistry() {
            @Override
            public RpcMessageSpec<? extends MessageLite> find(int candidate) {
                return candidate == rpcNum ? spec : null;
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
    }

    private interface TestMessage {
    }

    private interface TestRole {
    }

    private record TestCommand(String value) implements TestMessage {
    }

    private static final class RecordingActor extends RpcActor<TestMessage> {
        private final List<TestCommand> received;

        private RecordingActor(
                ActorContext<TestMessage> context,
                RpcMessageRegistry registry,
                List<TestCommand> received
        ) {
            super(context, "test-entity", registry);
            this.received = received;
        }

        private static Behavior<TestMessage> create(
                RpcMessageRegistry registry,
                List<TestCommand> received
        ) {
            return Behaviors.setup(context -> new RecordingActor(context, registry, received));
        }

        @Override
        public Receive<TestMessage> createReceive() {
            return newRpcReceiveBuilder()
                    .onMessage(TestCommand.class, command -> {
                        received.add(command);
                        return this;
                    })
                    .build();
        }
    }
}
