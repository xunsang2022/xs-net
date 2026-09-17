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

    private final UdpConfig config;
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
