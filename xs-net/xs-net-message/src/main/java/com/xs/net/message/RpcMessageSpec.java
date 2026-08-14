package com.xs.net.message;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

public record RpcMessageSpec<T extends MessageLite>(
        int rpcNum,
        Class<T> messageClass,
        Parser<T> parser,
        Class<?> roleClass
) {
}
