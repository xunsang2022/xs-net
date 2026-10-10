package com.game.eventsourcing.sync;

import java.util.Map;

/**
 * 一条脏记录：标识某个实体待同步的脏字段集合及其乐观锁版本。
 * 不可变记录。
 */
public record DirtyRecord(
        String entityType,
        Long entityId,
        Map<String, String> fields,
        long expectedVersion,
        long newVersion
) {
    public long version() {
        return newVersion;
    }

    /** 返回该记录在脏缓冲区 / 重试状态中的复合键。 */
    public RecordKey key() {
        return new RecordKey(entityType, entityId);
    }
}