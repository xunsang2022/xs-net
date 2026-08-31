package com.game.message.scene;

import com.game.message.actor.logic.SceneMessage;
import com.game.message.annotation.ProtoField;
import com.game.message.annotation.Rpc;

@Rpc(kind = Rpc.Kind.INTERNAL, number = 3006)
public record SceneJoinCommand(@ProtoField(number = 1) Long playerId) implements SceneMessage {
}
