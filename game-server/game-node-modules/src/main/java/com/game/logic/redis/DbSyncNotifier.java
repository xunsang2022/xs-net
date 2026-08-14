package com.game.logic.redis;

/**
 * Callback used after a Redis write so the caller can notify the db-sync shard.
 */
@FunctionalInterface
public interface DbSyncNotifier {

    void notify(String playerId, String op);
}
