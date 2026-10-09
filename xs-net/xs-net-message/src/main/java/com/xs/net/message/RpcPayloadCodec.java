package com.xs.net.message;

import com.google.protobuf.MessageLite;

/**
 * RPC消息编解码器接口，定义DTO与Protocol Buffers消息之间的转换规则。
 *
 * @param <D> DTO类型
 * @param <P> Protocol Buffers消息类型
 */
public interface RpcPayloadCodec<D, P extends MessageLite> {
    /**
     * 将DTO编码为Protocol Buffers消息。
     *
     * @param dto DTO对象
     * @return Protocol Buffers消息
     */
    P encode(D dto);

    /**
     * 将Protocol Buffers消息解码为DTO。
     *
     * @param payload Protocol Buffers消息
     * @return DTO对象
     */
    D decode(P payload);
}
