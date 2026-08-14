package com.xs.net.message;

import com.google.protobuf.MessageLite;

public interface RpcEnvelopeFactory {
    RpcMessage create(int rpcNum, MessageLite payload);
}
