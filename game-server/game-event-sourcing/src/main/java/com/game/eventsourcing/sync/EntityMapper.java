package com.game.eventsourcing.sync;

import java.util.List;
import java.util.Map;

/**
 * 实体持久化契约：共享 DBSyncEngine 通过 {@link EntityPersistenceRouter} 按 entityType 分发到对应实现。
 * 新增实体只需实现本接口（含 MyBatis XML statement）并注册到共享引擎的 mapper 列表，无需改动引擎核心。
 */
public interface EntityMapper {

    /** 实体类型标识（如 "player"），与调用方 submit / loadOrCreate / saveNow 传入的 entityType 一致。 */
    String entityType();

    /**
     * 单条 UPSERT。
     *
     * @return 成功写入的实体 ID；null 表示未写入（如乐观锁冲突）
     */
    Long upsertRecord(DirtyRecord record);

    /**
     * 批量 UPSERT。
     *
     * @return 本批次中成功写入且未发生乐观锁冲突的实体 ID 集合，不得包含批次外 ID；无成功记录时返回空集合
     */
    List<Long> upsertBatch(List<DirtyRecord> records);

    /** 读取实体状态，不存在时创建默认记录。 */
    Map<String, String> loadOrCreate(Long entityId);
}