package com.game.eventsourcing.sync;

import java.util.Objects;

/**
 * 脏记录与重试状态的复合键：实体类型 + 实体 ID。
 * 不同实体的相同 ID（如 player#1 与 guild#1）互不干扰。
 */
public record RecordKey(String entityType, Long entityId) {
    public RecordKey {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
    }
}