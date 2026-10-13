package com.xs.kernel.actor;

import com.xs.kernel.actor.node.Roles;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ActorServicesTest {

    @Roles("logic")
    private interface PlayerMessage {
    }

    @Roles("battle")
    private interface BattleMessage {
    }

    private interface Marker {
    }

    private interface InheritedRole extends PlayerMessage {
    }

    private record LoginCommand(String playerId) implements InheritedRole, Marker {
    }

    private record AmbiguousCommand(String value) implements PlayerMessage, BattleMessage {
    }

    private record PlainCommand(String value) {
    }

    @Test
    public void resolvesRoleFromImplementedInterface() {
        assertEquals(PlayerMessage.class, ActorServices.roleOf(LoginCommand.class));
    }

    @Test
    public void rejectsMessageWithoutSingleRole() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ActorServices.roleOf(AmbiguousCommand.class)).getMessage().contains("exactly one @Roles"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ActorServices.roleOf(PlainCommand.class)).getMessage().contains("exactly one @Roles"));
    }
}
