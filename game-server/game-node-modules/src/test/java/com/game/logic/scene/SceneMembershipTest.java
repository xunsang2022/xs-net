package com.game.logic.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneMembershipTest {

    @Test
    void maintainsAnIdempotentMembershipSet() {
        SceneMembership membership = new SceneMembership();

        assertTrue(membership.join(101L));
        assertFalse(membership.join(101L));
        assertTrue(membership.join(202L));
        assertEquals(2, membership.size());
        assertTrue(membership.contains(101L));
        assertTrue(membership.leave(101L));
        assertFalse(membership.leave(101L));
        assertEquals(1, membership.size());
    }

    @Test
    void ignoresALateLeaveFromThePreviousScene() {
        PlayerSceneTracker tracker = new PlayerSceneTracker();

        assertTrue(tracker.join("scene-a"));
        assertTrue(tracker.join("scene-b"));
        assertFalse(tracker.leave("scene-a"));
        assertEquals("scene-b", tracker.currentSceneId());
        assertTrue(tracker.leave("scene-b"));
        assertEquals(null, tracker.currentSceneId());
    }
}
