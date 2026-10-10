package com.game.logic.actor.player.db;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.game.eventsourcing.sync.DirtyRecord;
import com.game.eventsourcing.sync.EntityMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** Mapper containing the PostgreSQL-specific player persistence statements. */
public interface PlayerMapper extends BaseMapper<PlayerPO>, EntityMapper {

    /** 玩家实体在同步引擎中的类型标识。 */
    String PLAYER_ENTITY_TYPE = "player";

    PlayerPO upsert(PlayerPO player);

    PlayerPO loadOrCreate(@Param("playerId") Long playerId,
                          @Param("defaultName") String defaultName);

    @Override
    Long upsertRecord(@Param("record") DirtyRecord record);

    @Override
    List<Long> upsertBatch(@Param("records") List<DirtyRecord> records);

    @Override
    default Map<String, String> loadOrCreate(Long entityId) {
        return loadOrCreate(entityId, PLAYER_ENTITY_TYPE + "-" + entityId).toHash();
    }

    @Override
    default String entityType() {
        return PLAYER_ENTITY_TYPE;
    }
}