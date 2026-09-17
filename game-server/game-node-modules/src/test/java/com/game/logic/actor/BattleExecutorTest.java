package com.game.logic.actor;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleExecutorTest {

    @Test
    void serializesOneBattleOnBattleExecutorThreadAndClosesCleanly() throws Exception {
        BattleExecutorConfig config = new BattleExecutorConfig(2, 32, 32, 1000, 60);
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

        try (BattleExecutorPool pool = new BattleExecutorPool(config)) {
            BattleRuntime runtime = pool.activate("battle-1");
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleCreateCommand("battle-1"), context));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleJoinCommand(42L), context));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    pool.submit("battle-1", new BattleInputCommand(42L, 1L, 1, 0), context));

            awaitTrue(() -> runtime.lastExecutionThreadName() != null);
            assertTrue(runtime.lastExecutionThreadName().startsWith("battle-executor-"));
            assertEquals(1, runtime.maxConcurrentDrains());
            assertEquals(1, pool.activeLanes());
        }

        try (BattleExecutorPool closedPool = new BattleExecutorPool(config)) {
            closedPool.close();
            assertEquals(BattleSubmissionResult.CLOSED,
                    closedPool.submit("battle-closed", new BattleInputCommand(1L, 1L, 0, 0), context));
        }
    }

    @Test
    void coalescesHighFrequencyInputWithoutCreatingOneTaskPerInput() throws Exception {
        BattleExecutorConfig config = new BattleExecutorConfig(1, 32, 512, 1000, 60);
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        BattleRuntime runtime = new BattleRuntime("battle-1", config, task -> true, ticker, (id, closed) -> {
        });
        try {
            int maxPendingInputs = 0;
            for (long sequence = 1; sequence <= 100_000; sequence++) {
                runtime.submitInput(new BattleInputCommand(42L, sequence, 1, 0), context);
                maxPendingInputs = Math.max(maxPendingInputs, runtime.pendingInputCount());
            }

            assertTrue(maxPendingInputs <= 1,
                    "max pending inputs=" + maxPendingInputs + ", players=" + runtime.pendingInputPlayers());
        } finally {
            runtime.close();
            ticker.shutdownNow();
        }
    }

    private static void awaitTrue(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!check.value() && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertTrue(check.value(), "condition was not satisfied before timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }
}
