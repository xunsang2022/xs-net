package com.game.battle.actor;

import com.xs.core.actor.node.ActorDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BattleActorDispatcherTest {

    @Test
    void battleActorUsesOnlyTheLifecycleDispatcher() {
        ActorDispatcher dispatcher = BattleActor.class.getAnnotation(ActorDispatcher.class);
        assertNotNull(dispatcher);
        assertEquals("pekko.actor.battle-control-dispatcher", dispatcher.value());
    }
}
