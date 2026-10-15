package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import com.game.gateway.config.UdpConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UDP 服务器：承载客户端轻量可靠 UDP 上行与 S2C 下行。
 * <p>单 I/O 线程绑定 {@link NioDatagramChannel}，装配 {@link UdpAuthHandler}；
 * 后台定时线程负责在途包重传与空闲会话清理。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class UdpServer {

    /** UDP 端口、可靠层和线程池配置。 */
    @NonNull
    private final UdpConfig config;
    /** UDP 握手阶段复用的 JWT 验签器。 */
    @NonNull
    private final JwtUtil jwtUtil;

    private EventLoopGroup group;
    private DatagramChannel serverChannel;
    private ScheduledExecutorService scheduler;
    private ExecutorService businessExecutor;
    private UdpAuthHandler authHandler;

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(config.resolveThreads());
        // 业务线程池：承载解码/验签/建会话/回 ACK 等 CPU 密集处理，避免单 channel 单 event loop 串行瓶颈
        businessExecutor = Executors.newFixedThreadPool(config.resolveBusinessThreads(), r -> {
            Thread t = new Thread(r, "udp-business");
            t.setDaemon(true);
            return t;
        });
        authHandler = new UdpAuthHandler(jwtUtil, config, businessExecutor);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                // 数据报套接字默认接收缓冲仅约 64KB，高并发握手突发会直接丢包（UDP 无内核重传）；
                // 显式放大收发缓冲，避免上行握手报文在内核层被丢弃。
                .option(ChannelOption.SO_RCVBUF, config.getSocketBufferBytes())
                .option(ChannelOption.SO_SNDBUF, config.getSocketBufferBytes())
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(authHandler);
                    }
                });
        serverChannel = (DatagramChannel) bootstrap.bind(config.getPort()).sync().channel();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "udp-reliable-maintenance");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(authHandler::retryAndCleanup, 1, 1, TimeUnit.SECONDS);

        log.info("UdpServer started on port {} (ioThreads={}, business={})",
                config.getPort(), config.resolveThreads(), config.resolveBusinessThreads());
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (businessExecutor != null) {
            businessExecutor.shutdownNow();
        }
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        log.info("UdpServer stopped");
    }
}
