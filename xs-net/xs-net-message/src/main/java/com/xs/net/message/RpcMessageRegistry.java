package com.xs.net.message;

import com.google.protobuf.MessageLite;

import java.util.List;

public interface RpcMessageRegistry {
    RpcMessageSpec<? extends MessageLite> find(int rpcNum);

    RpcMessageSpec<? extends MessageLite> findByClass(Class<? extends MessageLite> messageClass);

    List<RpcMessageSpec<? extends MessageLite>> all();
}
