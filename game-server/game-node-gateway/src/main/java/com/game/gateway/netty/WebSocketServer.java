package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import com.game.gateway.config.WebSocketConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Separate WebSocket listener; TCP and UDP Gateway listeners remain unchanged. */
@Slf4j
@RequiredArgsConstructor
public final class WebSocketServer {

    private final NettyConfig nettyConfig;
    private final WebSocketConfig webSocketConfig;
    private final JwtUtil jwtUtil;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessGroup;
    private Channel channel;

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(nettyConfig.resolveThreads(nettyConfig.getBossThreads()));
        workerGroup = new NioEventLoopGroup(nettyConfig.resolveThreads(nettyConfig.getWorkerThreads()));
        businessGroup = new DefaultEventExecutorGroup(nettyConfig.resolveThreads(nettyConfig.getBusinessThreads()));
        channel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new WebSocketChannelInitializer(jwtUtil, nettyConfig, webSocketConfig, businessGroup))
                .option(ChannelOption.SO_BACKLOG, nettyConfig.getBacklog())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .bind(webSocketConfig.getPort()).sync().channel();
        log.info("WebSocket server started: ws://0.0.0.0:{}{}", webSocketConfig.getPort(), webSocketConfig.getPath());
    }

    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (businessGroup != null) {
            businessGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
