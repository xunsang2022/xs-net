package com.game.logic.actor;

import com.xs.core.actor.node.ActorDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BattleActorDispatcherTest {

    @Test
    void battleActorDoesNotDeclareAnActorDispatcher() {
        ActorDispatcher dispatcher = BattleActor.class.getAnnotation(ActorDispatcher.class);
        assertNull(dispatcher);
    }

    @Test
    void keepsRealtimeSceneAndPlayerActorsOnTheirDedicatedDispatchers() {
        ActorDispatcher scene = SceneActor.class.getAnnotation(ActorDispatcher.class);
        ActorDispatcher player = PlayerActor.class.getAnnotation(ActorDispatcher.class);

        assertEquals("pekko.actor.scene-dispatcher", scene.value());
        assertEquals("pekko.actor.player-dispatcher", player.value());
    }
}
