package com.game.logic.management;

import com.game.logic.actor.player.PlayerActor;
import com.game.logic.actor.scene.SceneActor;
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
        assertEquals(3003, target.effectiveProtocolNumber());
        assertEquals(PlayerActor.class, target.actorType());
        assertEquals("onPlayerEnterMessage", target.handlerMethod());
    }

    @Test
    void resolvesSceneProtocolToSceneHandler() {
        ProtocolTraceTarget target = resolver.resolve(3006);

        assertEquals(SceneActor.class, target.actorType());
        assertEquals("onJoin", target.handlerMethod());
    }

    @Test
    void rejectsProtocolThatDoesNotEnterLogic() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(1002));
    }
}
