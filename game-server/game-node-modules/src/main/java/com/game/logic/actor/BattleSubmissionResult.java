package com.game.logic.actor;

public enum BattleSubmissionResult {
    ACCEPTED,
    COALESCED,
    STALE,
    REJECTED_CONTROL_CAPACITY,
    REJECTED_INPUT_CAPACITY,
    UNKNOWN_BATTLE,
    EXECUTOR_REJECTED,
    CLOSED
}
