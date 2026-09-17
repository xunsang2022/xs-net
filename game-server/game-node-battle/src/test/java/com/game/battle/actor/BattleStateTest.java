package com.game.battle.actor;

import com.game.message.battle.BattleAttackCommand;
import com.game.message.battle.BattleInputCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleStateTest {

    @Test
    void createsJoinsAndAdvancesOneFrame() {
        BattleState state = new BattleState("battle-1", 100);

        assertTrue(state.create());
        assertFalse(state.create());
        assertNull(state.join(42L));
        assertNull(state.acceptInput(new BattleInputCommand(42L, 1L, 1, -1)));

        state.tick();

        assertEquals(1L, state.frameNumber());
        assertEquals(1L, state.lastSnapshot().frameNumber());
        BattleState.ParticipantSnapshot player = state.lastSnapshot().participants().get(42L);
        assertEquals(1, player.positionX());
        assertEquals(-1, player.positionY());
        assertEquals(0, player.directionX());
        assertEquals(0, player.directionY());
    }

    @Test
    void usesLatestAcceptedInputPerFrameAndRejectsStaleSequence() {
        BattleState state = createdState();
        assertNull(state.join(42L));
        assertNull(state.acceptInput(new BattleInputCommand(42L, 1L, 1, 0)));
        assertNull(state.acceptInput(new BattleInputCommand(42L, 2L, 0, 1)));
        assertEquals("stale_sequence", state.acceptInput(new BattleInputCommand(42L, 2L, -1, 0)));

        state.tick();

        BattleState.ParticipantSnapshot player = state.lastSnapshot().participants().get(42L);
        assertEquals(0, player.positionX());
        assertEquals(1, player.positionY());
        assertEquals(2L, player.lastClientSequence());
    }

    @Test
    void appliesBaseAttackAgainstDefenseAndExposesHealthInSnapshot() {
        BattleState state = createdState();
        assertNull(state.join(42L));
        assertNull(state.join(43L));

        BattleState.ParticipantSnapshot attacker = state.participant(42L);
        assertEquals(BattleState.DEFAULT_ATTACK, attacker.attack());
        assertEquals(BattleState.DEFAULT_DEFENSE, attacker.defense());
        assertEquals(BattleState.DEFAULT_MAX_HP, attacker.hp());
        assertEquals(BattleState.DEFAULT_MAX_HP, attacker.maxHp());

        assertNull(state.attack(new BattleAttackCommand(42L, 43L, 1L)));
        BattleState.ParticipantSnapshot target = state.participant(43L);
        assertEquals(BattleState.DEFAULT_MAX_HP
                        - (BattleState.DEFAULT_ATTACK - BattleState.DEFAULT_DEFENSE),
                target.hp());
        assertEquals("stale_sequence", state.attack(new BattleAttackCommand(42L, 43L, 1L)));
    }

    @Test
    void rejectsAttacksFromUnknownOrDefeatedParticipants() {
        BattleState state = createdState();
        assertNull(state.join(42L));

        assertEquals("unknown_target", state.attack(new BattleAttackCommand(42L, 43L, 1L)));
        assertEquals("unknown_attacker", state.attack(new BattleAttackCommand(43L, 42L, 1L)));
    }

    @Test
    void rejectsInvalidInputsAndUnknownPlayersWithoutChangingState() {
        BattleState state = createdState();
        assertNull(state.join(42L));

        assertEquals("unknown_player", state.acceptInput(new BattleInputCommand(7L, 1L, 1, 0)));
        assertEquals("invalid_direction", state.acceptInput(new BattleInputCommand(42L, 1L, 2, 0)));
        assertEquals("stale_sequence", state.acceptInput(new BattleInputCommand(42L, 0L, 1, 0)));

        state.tick();

        BattleState.ParticipantSnapshot player = state.lastSnapshot().participants().get(42L);
        assertEquals(0, player.positionX());
        assertEquals(0, player.positionY());
        assertEquals(0L, player.lastClientSequence());
    }

    @Test
    void enforcesCapacityAndEndsCleanly() {
        BattleState state = new BattleState("battle-1", 2);
        assertTrue(state.create());
        assertNull(state.join(1L));
        assertNull(state.join(2L));
        assertEquals("capacity_exceeded", state.join(3L));
        assertTrue(state.leave(1L));
        assertFalse(state.isEmpty());
        assertTrue(state.leave(2L));
        assertTrue(state.isEmpty());

        assertTrue(state.end());
        assertFalse(state.end());
        assertEquals("battle_ended", state.join(4L));
        assertEquals("battle_ended", state.acceptInput(new BattleInputCommand(4L, 1L, 1, 0)));
    }

    private static BattleState createdState() {
        BattleState state = new BattleState("battle-1", 100);
        assertTrue(state.create());
        return state;
    }
}
