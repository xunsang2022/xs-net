package com.game.eventsourcing.log;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** PostgreSQL mapper for idempotent log event inserts. */
public interface LogEventMapper {

    List<String> insertIgnoreBatch(@Param("events") List<LogEventPO> events);
}
