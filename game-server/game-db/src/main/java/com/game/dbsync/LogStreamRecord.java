package com.game.dbsync;

import java.util.Map;

public record LogStreamRecord(String id, Map<String, String> fields) {
}
