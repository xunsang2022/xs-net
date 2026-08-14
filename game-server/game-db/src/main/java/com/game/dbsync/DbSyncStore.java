package com.game.dbsync;

import java.util.List;
import java.util.Map;

public interface DbSyncStore {
    Map<String, String> loadPlayerHash(Long playerId);

    Map<String, String> loadOrCreatePlayer(Long playerId);

    int[] batchUpsertPlayers(List<Map<String, String>> records);

    void markSynced(Long playerId);

    void writePlayerHashAndIndex(Long playerId, Map<String, String> fields);

    long logBacklog();

    List<LogStreamRecord> readLogEvents(int batchSize);

    void ackLogEvents(List<String> recordIds);
}
