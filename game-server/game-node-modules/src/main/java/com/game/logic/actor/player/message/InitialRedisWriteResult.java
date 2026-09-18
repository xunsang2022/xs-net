package com.game.logic.actor.player.message;

import com.game.message.actor.logic.PlayerMessage;

/**
 * 玩家首次上线状态写入 Redis 完成后的内部结果消息。
 */
public record InitialRedisWriteResult(Long playerId, Boolean success, Throwable error) implements PlayerMessage {
}
