package com.game.message.rpc;

import com.game.message.actor.S2C;
import com.game.message.actor.XSSystem;
import com.game.message.actor.logic.C2S;
import com.game.message.actor.logic.PlayerMessage;
import com.google.protobuf.MessageLite;
import com.xs.net.message.INetMessage;
import com.xs.net.message.RpcMessage;

public final class GameRpcEnvelope implements RpcMessage, PlayerMessage, S2C, XSSystem, C2S, INetMessage {

    private final int rpcNum;
    private final MessageLite payload;

    public GameRpcEnvelope(int rpcNum, MessageLite payload) {
        this.rpcNum = rpcNum;
        this.payload = payload;
    }

    @Override
    public int rpcNum() {
        return rpcNum;
    }

    @Override
    public MessageLite payload() {
        return payload;
    }
}
