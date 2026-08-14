package com.game.message.rpc;

import com.google.protobuf.MessageLite;
import com.xs.net.message.RpcEnvelopeFactory;
import com.xs.net.message.RpcMessage;

public class GameRpcEnvelopeFactory implements RpcEnvelopeFactory {

    @Override
    public RpcMessage create(int rpcNum, MessageLite payload) {
        return new GameRpcEnvelope(rpcNum, payload);
    }
}
