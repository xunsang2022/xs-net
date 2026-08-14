package com.game.dbsync;

import com.game.message.actor.db.DbSyncOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DbSyncLedger {
    private final Map<Long, DbSyncOp> dirty = new ConcurrentHashMap<>();

    public void mark(Long playerId, DbSyncOp op) {
        if (playerId == null) {
            return;
        }
        dirty.put(playerId, op == null ? DbSyncOp.UPDATE : op);
    }

    public boolean contains(Long playerId) {
        return dirty.containsKey(playerId);
    }

    public int size() {
        return dirty.size();
    }

    public List<Long> snapshot() {
        return new ArrayList<>(dirty.keySet());
    }

    public Map<Long, DbSyncOp> snapshotOps() {
        return new ConcurrentHashMap<>(dirty);
    }

    public void synced(Long playerId) {
        dirty.remove(playerId);
    }
}
