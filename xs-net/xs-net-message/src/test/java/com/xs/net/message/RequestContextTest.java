package com.xs.net.message;

import com.xs.net.message.pb.NetMessage;
import junit.framework.TestCase;

import java.time.Duration;

public class RequestContextTest extends TestCase {
    public void testRoundTripsContextThroughNetMessage() {
        RequestContext original = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));
        NetMessage message = original.applyTo(NetMessage.newBuilder().setRpcNum(1000)).build();

        RequestContext restored = RequestContext.from(message);

        assertEquals(original.traceId(), restored.traceId());
        assertEquals(original.playerId(), restored.playerId());
        assertEquals(original.channelId(), restored.channelId());
        assertEquals(original.deadlineEpochMs(), restored.deadlineEpochMs());
    }

    public void testNextHopIncrementsHopCount() {
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));
        assertEquals(1, context.nextHop("logic-1").hopCount());
    }

    public void testLegacyEnvelopeGetsTraceAndDeadline() {
        RequestContext restored = RequestContext.from(NetMessage.newBuilder().setRpcNum(1000).build());

        assertNotNull(restored.traceId());
        assertTrue(restored.deadlineEpochMs() >= System.currentTimeMillis());
        assertFalse(restored.expired());
    }
}
