package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;

/**
 * 客户端通道端点抽象。
 * <p>统一 TCP 长连接 Channel 与 UDP 会话两种形态，供 ChannelActor 下行 S2C 时写回。</p>
 */
public interface ClientEndpoint {

    /**
     * 将 NetMessage 编码后写回客户端。
     */
    void write(NetMessage message);

    /**
     * 底层连接/会话是否仍可用。
     */
    boolean isActive();

    /**
     * 关闭底层连接/会话并清理资源。
     */
    void close();
}
