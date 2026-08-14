package com.xs.net.message;

import com.google.protobuf.MessageLite;

public interface RpcMessage {
    int rpcNum();

    MessageLite payload();
}
