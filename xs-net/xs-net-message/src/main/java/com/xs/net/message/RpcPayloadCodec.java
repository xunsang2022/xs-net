package com.xs.net.message;

import com.google.protobuf.MessageLite;

public interface RpcPayloadCodec<D, P extends MessageLite> {
    P encode(D dto);

    D decode(P payload);
}
