package com.game.message.scene;

public record SceneId(String mapId, int lineNumber) {

    public SceneId {
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("mapId must not be blank");
        }
        if (lineNumber <= 0) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
    }

    public String value() {
        return mapId + ':' + lineNumber;
    }
}
