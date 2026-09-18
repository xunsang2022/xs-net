package com.game.message.scene;

import com.game.message.actor.logic.PlayerMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

/** 通知玩家 Actor 其场景加入状态发生变化的内部消息。 */
@Rpc(kind = Rpc.Kind.INTERNAL, number = 3008)
public record PlayerSceneChanged(
        @ProtoField(number = 1) String sceneId,
        @ProtoField(number = 2) Boolean joined
) implements PlayerMessage {
}
