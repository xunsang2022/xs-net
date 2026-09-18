package com.game.logic.actor.player.message;

import com.game.message.actor.logic.PlayerMessage;

import java.util.Map;

/**
 * 玩家状态从数据库加载完成后的内部结果消息。
 */
public record DatabaseLoadResult(Long playerId, Map<String, String> fields, Throwable error) implements PlayerMessage {
}
