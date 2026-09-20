package com.game.logic.dbsync;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Mapper containing the PostgreSQL-specific player persistence statements. */
public interface PlayerMapper extends BaseMapper<PlayerPO> {

    PlayerPO loadOrCreate(@Param("playerId") Long playerId,
                          @Param("defaultName") String defaultName);

    List<PlayerPO> upsertBatch(@Param("players") List<PlayerPO> players);
}
