package com.game.message.c2s;

import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;
import com.game.message.routing.ChannelRoutable;
import com.game.message.routing.PlayerRoutable;

/** 客户端请求登录并建立玩家与会话关联的消息。 */
@Rpc(kind = Rpc.Kind.MESSAGE, number = 1000)
public record LoginRequest(
        @ProtoField(number = 1) String playerId,
        @ProtoField(number = 2) String playerName,
        @ProtoField(number = 3) String sessionId
) implements PlayerRoutable, ChannelRoutable {
    @Override
    public String channelId() {
        return sessionId;
    }
}
