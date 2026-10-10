package com.game.eventsourcing.log;

import java.util.Map;

/**
 * Redis Streams 中的一条日志记录。
 */
public record LogStreamRecord(String id, Map<String, String> fields) {
}
