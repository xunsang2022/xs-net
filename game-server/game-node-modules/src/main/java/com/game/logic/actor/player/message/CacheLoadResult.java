package com.game.logic.actor.player.message;

import com.game.message.actor.logic.PlayerMessage;

import java.util.Map;

/**
 * 玩家状态从 Redis 缓存加载完成后的内部结果消息。
 */
public record CacheLoadResult(Long playerId, Map<String, String> fields, Throwable error) implements PlayerMessage {
}
