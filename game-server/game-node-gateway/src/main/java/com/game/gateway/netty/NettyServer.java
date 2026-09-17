package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.NettyConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty TCP 长连接服务器：承载客户端游戏消息上行与 S2C 下行。
 * <p>
 * 线程模型：
 * <ul>
 *   <li>boss：Acceptor 线程，默认 1；</li>
 *   <li>worker：I/O 事件循环线程，默认 2 * CPU；</li>
 *   <li>business：业务线程池（{@link DefaultEventExecutorGroup}），承载验签 / 解码 / 投递等 CPU
 *       密集逻辑，避免阻塞 I/O 事件循环。默认 2 * CPU。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class NettyServer {

    private final NettyConfig config;
    private final JwtUtil jwtUtil;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessGroup;
    private Channel serverChannel;

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(config.resolveThreads(config.getBossThreads()));
        workerGroup = new NioEventLoopGroup(config.resolveThreads(config.getWorkerThreads()));
        // 业务线程池：承载 AuthHandler 中的验签/解码/投递，避免阻塞 I/O 线程
        businessGroup = new DefaultEventExecutorGroup(config.resolveThreads(config.getBusinessThreads()));

        WriteBufferWaterMark waterMark = new WriteBufferWaterMark(
                config.getWriteBufferLowWaterMark(), config.getWriteBufferHighWaterMark());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new NettyChannelInitializer(jwtUtil, businessGroup, config))
                .option(ChannelOption.SO_BACKLOG, config.getBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark);
        serverChannel = bootstrap.bind(config.getPort()).sync().channel();
        log.info("NettyServer started on port {} (boss={}, worker={}, business={})",
                config.getPort(),
                config.resolveThreads(config.getBossThreads()),
                config.resolveThreads(config.getWorkerThreads()),
                config.resolveThreads(config.getBusinessThreads()));
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
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
        log.info("NettyServer stopped");
    }
}
