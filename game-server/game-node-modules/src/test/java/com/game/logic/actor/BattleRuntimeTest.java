package com.game.logic.actor;

import com.game.message.battle.BattleCreateCommand;
import com.game.message.battle.BattleInputCommand;
import com.xs.net.message.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BattleRuntimeTest {

    @Test
    void controlQueueIsBoundedAndInputIsCoalescedByPlayer() {
        BattleExecutorConfig config = new BattleExecutorConfig(1, 1, 8, 1000, 60);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        RequestContext context = RequestContext.root("42", "channel-1", Duration.ofSeconds(5));
        BattleRuntime runtime = new BattleRuntime("battle-1", config, task -> true, ticker, (id, closed) -> {
        });

        try {
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    runtime.submitControl(new BattleCreateCommand("battle-1"), context));
            assertEquals(BattleSubmissionResult.REJECTED_CONTROL_CAPACITY,
                    runtime.submitControl(new BattleCreateCommand("battle-1"), context));
            assertEquals(BattleSubmissionResult.ACCEPTED,
                    runtime.submitInput(new BattleInputCommand(42L, 1L, 1, 0), context));
            assertEquals(BattleSubmissionResult.COALESCED,
                    runtime.submitInput(new BattleInputCommand(42L, 2L, 0, 1), context));
            assertEquals(BattleSubmissionResult.STALE,
                    runtime.submitInput(new BattleInputCommand(42L, 1L, -1, 0), context));
        } finally {
            runtime.close();
            ticker.shutdownNow();
        }
    }
}
