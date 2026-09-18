package com.game.logic.actor.player.message;

import com.game.message.actor.logic.PlayerMessage;

import java.util.Map;

/**
 * 玩家登出或停止时，在线状态销毁完成后的内部结果消息。
 */
public record DestroyResult(Map<String, String> stateSnapshot, long version, Boolean success,
                            Throwable error) implements PlayerMessage {
}
