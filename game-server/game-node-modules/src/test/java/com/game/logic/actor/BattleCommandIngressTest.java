package com.game.logic.actor;

import com.game.message.actor.logic.BattleMessage;
import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleInputCommand;
import com.xs.net.message.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleCommandIngressTest {

    @Test
    void delegatesToClusterShardingUntilLocalRuntimeExists() {
        BattleExecutorConfig config = new BattleExecutorConfig(1, 8, 8, 1000, 60);
        try (BattleExecutorPool pool = new BattleExecutorPool(config)) {
            BattleCommandIngress ingress = new BattleCommandIngress(pool);
            RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

            assertFalse(ingress.intercept(null, BattleMessage.class,
                    new BattleInputCommand(42L, 1L, 0, 0), "battle-1", context));

            ingress.activate("battle-1");
            assertTrue(ingress.intercept(null, BattleMessage.class,
                    new BattleInputCommand(42L, 1L, 0, 0), "battle-1", context));
        }
    }

    @Test
    void acceptsLocalCreateThroughTheSameIngress() {
        BattleExecutorConfig config = new BattleExecutorConfig(1, 8, 8, 1000, 60);
        try (BattleExecutorPool pool = new BattleExecutorPool(config)) {
            BattleCommandIngress ingress = new BattleCommandIngress(pool);
            RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

            assertTrue(ingress.submit("battle-1", new BattleCreateCommand("battle-1"), context)
                    == BattleSubmissionResult.ACCEPTED);
            assertTrue(ingress.intercept(null, BattleMessage.class,
                    new BattleInputCommand(42L, 2L, 1, 0), "battle-1", context));
        }
    }
}
