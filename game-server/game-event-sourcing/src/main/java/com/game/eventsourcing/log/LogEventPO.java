package com.game.eventsourcing.log;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.sql.Timestamp;

/** Parameters for a log_events insert. */
@Data
@AllArgsConstructor
public final class LogEventPO {

    private final String sourceStreamId;
    private final int level;
    private final int type;
    private final String message;
    private final Long playerId;
    private final Timestamp createdAt;

}
