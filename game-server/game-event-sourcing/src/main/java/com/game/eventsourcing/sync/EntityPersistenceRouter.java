package com.game.eventsourcing.sync;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享引擎内的多实体分发器：按 entityType 把脏记录路由到对应 {@link EntityMapper}。
 * 批量写按 entityType 分组调用后合并成功键；未注册的 entityType 保留重试，不拖累其它实体。
 */
@Slf4j
public final class EntityPersistenceRouter implements DBSyncEngine.PersistenceCallback, DBSyncEngine.LoadCallback {

    private final Map<String, EntityMapper> mappers;

    public EntityPersistenceRouter(Collection<EntityMapper> mappers) {
        Map<String, EntityMapper> byType = new LinkedHashMap<>();
        for (EntityMapper mapper : mappers) {
            EntityMapper previous = byType.put(mapper.entityType(), mapper);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate EntityMapper for entityType: " + mapper.entityType());
            }
        }
        this.mappers = Map.copyOf(byType);
    }

    @Override
    public RecordKey upsertRecord(DirtyRecord record) {
        EntityMapper mapper = mapperFor(record.entityType());
        Long entityId = mapper.upsertRecord(record);
        return entityId == null ? null : new RecordKey(record.entityType(), entityId);
    }

    @Override
    public List<RecordKey> upsertBatch(List<DirtyRecord> records) {
        Map<String, List<DirtyRecord>> grouped = new LinkedHashMap<>();
        for (DirtyRecord record : records) {
            grouped.computeIfAbsent(record.entityType(), type -> new ArrayList<>()).add(record);
        }
        List<RecordKey> persisted = new ArrayList<>();
        for (Map.Entry<String, List<DirtyRecord>> entry : grouped.entrySet()) {
            String entityType = entry.getKey();
            EntityMapper mapper = mappers.get(entityType);
            if (mapper == null) {
                // 配置缺失：该组记录保留重试，不影响其它已注册实体的写入
                log.error("No EntityMapper registered, {} records retained for retry: entityType={}",
                        entry.getValue().size(), entityType);
                continue;
            }
            List<Long> ids = mapper.upsertBatch(entry.getValue());
            if (ids == null) {
                continue;
            }
            for (Long id : ids) {
                persisted.add(new RecordKey(entityType, id));
            }
        }
        return persisted;
    }

    @Override
    public Map<String, String> loadOrCreate(String entityType, Long entityId) {
        return mapperFor(entityType).loadOrCreate(entityId);
    }

    private EntityMapper mapperFor(String entityType) {
        EntityMapper mapper = mappers.get(entityType);
        if (mapper == null) {
            throw new IllegalArgumentException("No EntityMapper registered for entityType: " + entityType);
        }
        return mapper;
    }
}