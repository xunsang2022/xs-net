package com.game.battle.actor;

import com.game.message.battle.BattleInputCommand;
import com.xs.net.message.RequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 战斗专用执行器的本地 10 万次提交压测。 */
class BattleLoadTest {

    private static final int TOTAL_COMMANDS = 100_000;
    /** 与 BattleRuntime 的单场战斗玩家上限一致，避免把容量拒绝混入吞吐测试。 */
    private static final int PLAYERS = 64;

    @Test
    void submitsOneHundredThousandInputsWithoutMailboxBacklog() {
        BattleExecutorConfig config = new BattleExecutorConfig(8, 256, 512, 2_000, 60);
        RequestContext context = RequestContext.root("load-test", "load-channel", Duration.ofSeconds(30));

        try (BattleExecutorPool pool = new BattleExecutorPool(config)) {
            pool.activate("battle-load");
            long started = System.nanoTime();
            Map<BattleSubmissionResult, Integer> results = new EnumMap<>(BattleSubmissionResult.class);
            for (long sequence = 1; sequence <= TOTAL_COMMANDS; sequence++) {
                BattleSubmissionResult result = pool.submit(
                        "battle-load",
                        new BattleInputCommand((sequence - 1) % PLAYERS, sequence, 1, 0),
                        context);
                results.merge(result, 1, Integer::sum);
            }
            long elapsedNanos = System.nanoTime() - started;
            double qps = TOTAL_COMMANDS / (elapsedNanos / 1_000_000_000.0);

            System.out.printf("battle-load commands=%d elapsedMs=%.3f qps=%.0f accepted=%d coalesced=%d "
                            + "executorRejected=%d%n",
                    TOTAL_COMMANDS,
                    elapsedNanos / 1_000_000.0,
                    qps,
                    results.getOrDefault(BattleSubmissionResult.ACCEPTED, 0),
                    results.getOrDefault(BattleSubmissionResult.COALESCED, 0),
                    results.getOrDefault(BattleSubmissionResult.EXECUTOR_REJECTED, 0));
            assertEquals(TOTAL_COMMANDS, results.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(0, results.getOrDefault(BattleSubmissionResult.REJECTED_INPUT_CAPACITY, 0));
            assertEquals(0, results.getOrDefault(BattleSubmissionResult.UNKNOWN_BATTLE, 0));
            assertEquals(0, results.getOrDefault(BattleSubmissionResult.CLOSED, 0));
        }
    }
}
