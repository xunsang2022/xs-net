package com.game.battle.actor;

import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleInputCommand;
import com.game.message.battle.BattleJoinCommand;
import com.xs.net.message.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BattleExecutorTest {

    @Test
    void acceptsControlAndInputAndClosesCleanly() {
        BattleExecutorConfig config = new BattleExecutorConfig(2, 32, 32, 1000, 60);
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

        try (BattleExecutorPool pool = new BattleExecutorPool(config)) {
            assertNotNull(pool.activate("battle-1"));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleCreateCommand("battle-1"), context));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleJoinCommand(42L), context));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleInputCommand(42L, 1L, 1, 0), context));
        }

        try (BattleExecutorPool closedPool = new BattleExecutorPool(config)) {
            closedPool.close();
            assertEquals(BattleSubmissionResult.CLOSED,
                    closedPool.submit("battle-closed", new BattleInputCommand(1L, 1L, 0, 0), context));
        }
    }

    @Test
    void coalescesHighFrequencyInputWithoutCreatingOneTaskPerInput() {
        BattleExecutorConfig config = new BattleExecutorConfig(1, 32, 512, 1000, 60);
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        BattleRuntime runtime = new BattleRuntime("battle-1", config, task -> true, ticker, (id, closed) -> {
        });
        try {
            int accepted = 0;
            int coalesced = 0;
            for (long sequence = 1; sequence <= 100_000; sequence++) {
                BattleSubmissionResult result =
                        runtime.submitInput(new BattleInputCommand(42L, sequence, 1, 0), context);
                if (result == BattleSubmissionResult.ACCEPTED) {
                    accepted++;
                } else if (result == BattleSubmissionResult.COALESCED) {
                    coalesced++;
                }
            }

            assertEquals(1, accepted);
            assertEquals(99_999, coalesced);
        } finally {
            runtime.close();
            ticker.shutdownNow();
        }
    }
}
