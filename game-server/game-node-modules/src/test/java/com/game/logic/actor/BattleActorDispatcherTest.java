package com.game.logic.actor;

import com.xs.core.actor.node.ActorDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BattleActorDispatcherTest {

    @Test
    void declaresDedicatedBattleDispatcher() {
        ActorDispatcher dispatcher = BattleActor.class.getAnnotation(ActorDispatcher.class);
        assertNotNull(dispatcher);
        assertEquals("pekko.actor.battle-dispatcher", dispatcher.value());
    }
}
