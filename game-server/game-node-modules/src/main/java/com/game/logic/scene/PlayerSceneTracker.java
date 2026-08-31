package com.game.logic.scene;

import java.util.Objects;

/** Tracks the scene currently acknowledged by a PlayerActor. */
public final class PlayerSceneTracker {

    private String currentSceneId;

    public boolean join(String sceneId) {
        if (sceneId == null || sceneId.isBlank() || Objects.equals(currentSceneId, sceneId)) {
            return false;
        }
        currentSceneId = sceneId;
        return true;
    }

    public boolean leave(String sceneId) {
        if (!Objects.equals(currentSceneId, sceneId)) {
            return false;
        }
        currentSceneId = null;
        return true;
    }

    public String currentSceneId() {
        return currentSceneId;
    }
}
