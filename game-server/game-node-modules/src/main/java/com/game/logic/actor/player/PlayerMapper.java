package com.game.logic.actor.player;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.game.eventsourcing.DirtyRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Mapper containing the PostgreSQL-specific player persistence statements. */
public interface PlayerMapper extends BaseMapper<PlayerPO> {

    PlayerPO upsert(PlayerPO player);

    PlayerPO loadOrCreate(@Param("playerId") Long playerId,
                          @Param("defaultName") String defaultName);

    Long upsertRecord(@Param("record") DirtyRecord record);

    List<Long> upsertBatch(@Param("records") List<DirtyRecord> records);
}
