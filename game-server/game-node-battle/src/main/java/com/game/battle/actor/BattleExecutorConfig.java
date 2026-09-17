package com.game.battle.actor;

public record BattleExecutorConfig(
        int poolSize,
        int controlQueueCapacity,
        int maxInputsPerDrain,
        int shutdownTimeoutMs,
        int tickHz
) {
    public BattleExecutorConfig {
        if (poolSize < 1 || controlQueueCapacity < 1 || maxInputsPerDrain < 1
                || shutdownTimeoutMs < 1 || tickHz < 1) {
            throw new IllegalArgumentException("Battle executor configuration must be positive");
        }
    }

    public long tickPeriodNanos() {
        return 1_000_000_000L / tickHz;
    }
}
