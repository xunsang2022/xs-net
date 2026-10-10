package com.game.eventsourcing.sync;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 引擎路由表：调用方按 entityType 提交，未注册 override 的实体统一走 default 共享引擎。
 * 少数特殊实体（写入量远高 / 同步策略不同 / 失败需隔离）可通过 overrides 挂独立引擎，
 * 与共享引擎的容量、重试、终态回收完全隔离。
 */
public final class DBSyncEngineRegistry {

    private final DBSyncEngine defaultEngine;
    private final Map<String, DBSyncEngine> overrideEngines;

    public DBSyncEngineRegistry(DBSyncEngine defaultEngine, Map<String, DBSyncEngine> overrideEngines) {
        this.defaultEngine = Objects.requireNonNull(defaultEngine, "defaultEngine");
        this.overrideEngines = Map.copyOf(overrideEngines);
    }

    /** 返回 entityType 对应的引擎；未注册时回落 default 共享引擎。 */
    public DBSyncEngine engineFor(String entityType) {
        Objects.requireNonNull(entityType, "entityType");
        DBSyncEngine engine = overrideEngines.get(entityType);
        return engine == null ? defaultEngine : engine;
    }

    public boolean submit(String entityType, Long entityId, Map<String, String> fields, long version) {
        return engineFor(entityType).submit(entityType, entityId, fields, version);
    }

    public Map<String, String> loadOrCreate(String entityType, Long entityId) {
        return engineFor(entityType).loadOrCreate(entityType, entityId);
    }

    public boolean saveNow(String entityType, Long entityId, Map<String, String> snapshot, long version) {
        return engineFor(entityType).saveNow(entityType, entityId, snapshot, version);
    }

    /** 启动所有引擎（default + 独立引擎，同一实例只启动一次）。 */
    public void startAll() {
        engines().forEach(DBSyncEngine::start);
    }

    /** 刷新所有引擎的脏记录。 */
    public void flushAll() {
        engines().forEach(DBSyncEngine::flush);
    }

    /** 停止所有引擎。 */
    public void stopAll() {
        engines().forEach(DBSyncEngine::stop);
    }

    private Set<DBSyncEngine> engines() {
        Set<DBSyncEngine> all = new LinkedHashSet<>();
        all.add(defaultEngine);
        all.addAll(overrideEngines.values());
        return all;
    }
}