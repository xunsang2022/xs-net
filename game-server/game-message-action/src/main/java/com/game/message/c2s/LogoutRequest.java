package com.game.message.c2s;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;
import com.game.message.routing.ChannelRoutable;
import com.game.message.routing.PlayerRoutable;
import com.game.message.actor.logic.PlayerMessage;

/** 客户端请求玩家登出并关闭当前会话的请求消息。 */
@Rpc(kind = Rpc.Kind.MESSAGE, number = 1001)
public record LogoutRequest(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String sessionId
) implements PlayerMessage, PlayerRoutable, ChannelRoutable {
    @Override
    public String channelId() {
        return sessionId;
    }
}
