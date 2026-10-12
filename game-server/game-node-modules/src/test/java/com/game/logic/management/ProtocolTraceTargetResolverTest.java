package com.game.logic.management;

import com.game.logic.actor.player.PlayerActor;
import com.game.message.rpc.GameRpcMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolTraceTargetResolverTest {

    private final ProtocolTraceTargetResolver resolver = new ProtocolTraceTargetResolver(GameRpcMessages.INSTANCE);

    @Test
    void resolvesGatewayLoginToItsLogicHandler() {
        ProtocolTraceTarget target = resolver.resolve(1000);

        assertEquals(1000, target.requestedProtocolNumber());
        assertEquals(1000, target.effectiveProtocolNumber());
        assertEquals(PlayerActor.class, target.actorType());
        assertEquals("onLoginMessage", target.handlerMethod());
    }

    @Test
    void rejectsProtocolThatDoesNotEnterLogic() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(1002));
    }
}
