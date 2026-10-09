package com.game.eventsourcing;

import java.util.Map;

/**
 * 一条脏记录：标识某个玩家待同步的脏字段集合及其乐观锁版本。
 * 不可变记录。
 */
public record DirtyRecord(Long playerId, Map<String, String> fields, long expectedVersion, long newVersion) {

    public long version() {
        return newVersion;
    }
}
