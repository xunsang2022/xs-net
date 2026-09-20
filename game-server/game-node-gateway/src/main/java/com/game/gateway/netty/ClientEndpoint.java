package com.game.gateway.netty;

import com.xs.net.message.pb.NetMessage;
import com.xs.net.message.TransportKind;
import com.xs.net.transport.TransportSession;

/**
 * 客户端通道端点抽象。
 * <p>统一 TCP 长连接 Channel 与 UDP 会话两种形态，供 ChannelActor 下行 S2C 时写回。</p>
 */
public interface ClientEndpoint extends TransportSession {

    /**
     * 将 NetMessage 编码后写回客户端。
     */
    @Override
    void write(NetMessage message);

    /**
     * 底层连接/会话是否仍可用。
     */
    @Override
    boolean isActive();

    /** Stable logical connection random used to recognize an automatic reconnect. */
    default long connectionRandom() {
        return 0;
    }

    /**
     * 关闭底层连接/会话并清理资源。
     */
    @Override
    void close();

    @Override
    default String sessionId() {
        return "unknown";
    }

    @Override
    default TransportKind kind() {
        return TransportKind.TCP;
    }
}
