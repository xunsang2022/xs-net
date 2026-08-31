package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.game.gateway.config.WebSocketConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.concurrent.TimeUnit;

final class WebSocketChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final JwtUtil jwtUtil;
    private final NettyConfig nettyConfig;
    private final WebSocketConfig webSocketConfig;
    private final EventExecutorGroup businessExecutor;

    WebSocketChannelInitializer(JwtUtil jwtUtil, NettyConfig nettyConfig, WebSocketConfig webSocketConfig,
                                EventExecutorGroup businessExecutor) {
        this.jwtUtil = jwtUtil;
        this.nettyConfig = nettyConfig;
        this.webSocketConfig = webSocketConfig;
        this.businessExecutor = businessExecutor;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(webSocketConfig.getMaxFrameLength()));
        pipeline.addLast(new WebSocketServerProtocolHandler(
                webSocketConfig.getPath(), null, false, webSocketConfig.getMaxFrameLength()));
        if (nettyConfig.getReaderIdleTimeoutSeconds() > 0) {
            pipeline.addLast(new IdleStateHandler(nettyConfig.getReaderIdleTimeoutSeconds(), 0, 0, TimeUnit.SECONDS));
        }
        pipeline.addLast(businessExecutor, new WebSocketGameHandler(jwtUtil, nettyConfig));
    }
}
