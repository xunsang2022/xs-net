package com.game.logic.actor.player.message;

import com.game.message.actor.logic.PlayerMessage;

import java.util.Map;

/**
 * 玩家脏状态写入 Redis 完成后的内部结果消息。
 */
public record RedisFlushResult(Map<String, String> dirtyFields, Map<String, String> stateSnapshot, long version,
                               Boolean success, Throwable error) implements PlayerMessage {
}
