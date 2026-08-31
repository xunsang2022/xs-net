package com.game.logic.scene;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SceneMembership {

    private final Set<Long> playerIds = new LinkedHashSet<>();

    public boolean join(long playerId) {
        return playerIds.add(playerId);
    }

    public boolean leave(long playerId) {
        return playerIds.remove(playerId);
    }

    public boolean contains(long playerId) {
        return playerIds.contains(playerId);
    }

    public int size() {
        return playerIds.size();
    }
}
