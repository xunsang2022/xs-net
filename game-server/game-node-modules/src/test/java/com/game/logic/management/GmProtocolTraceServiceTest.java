package com.game.logic.management;

import com.game.message.rpc.GameRpcMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmProtocolTraceServiceTest {

    @Test
    void returnsArthasOutputForResolvedProtocol() {
        GmProtocolTraceService service = new GmProtocolTraceService(
                new ProtocolTraceTargetResolver(GameRpcMessages.INSTANCE),
                (target, timeoutMs) -> new ArthasTraceExecution(0, false, "trace output"));

        GmProtocolTraceService.TraceResult result = service.trace(1001, 1_000);

        assertEquals("COMPLETED", result.status());
        assertEquals("onLogoutMessage", result.target().handlerMethod());
        assertEquals("trace output", result.output());
        assertTrue(result.target().command().contains("PlayerActor onLogoutMessage"));
    }
}
