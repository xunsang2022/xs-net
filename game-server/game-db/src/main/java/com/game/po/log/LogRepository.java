package com.game.po.log;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 日志数据访问接口
 */
@Repository
public interface LogRepository extends JpaRepository<LogEntity, Long> {
    /**
     * 根据玩家 ID 查询日志
     */
    List<LogEntity> findByPlayerId(Long playerId);
    
    /**
     * 根据日志级别查询日志
     */
    List<LogEntity> findByLogLevel(Integer logLevel);
}
