package com.game.gateway.kcp;

import com.game.comment.util.JwtUtil;
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
 * 标准 KCP 可靠 UDP 服务器：监听 {@link KcpConfig#getPort()}。
 * <p>单 I/O 线程绑定 {@link NioDatagramChannel}，装配 {@link KcpAuthHandler}；
 * 后台 {@link ScheduledExecutorService} 周期驱动所有会话的 {@link Kcp#update} 与空闲/认证超时清理。
 * start()/stop() 由其生命周期所有方调用。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class KcpServer {

    private final KcpConfig config;
    private final JwtUtil jwtUtil;

    private EventLoopGroup group;
    private DatagramChannel serverChannel;
    private ScheduledExecutorService scheduler;
    private ExecutorService businessExecutor;
    private KcpAuthHandler authHandler;

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(config.resolveThreads());
        businessExecutor = Executors.newFixedThreadPool(config.resolveBusinessThreads(), r -> {
            Thread t = new Thread(r, "kcp-business");
            t.setDaemon(true);
            return t;
        });
        authHandler = new KcpAuthHandler(jwtUtil, config, businessExecutor);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                // 数据报套接字默认接收缓冲仅约 64KB，高并发握手突发会直接丢包（KCP 只能靠重传补救）；
                // 显式放大收发缓冲，避免上行报文在内核层被丢弃。
                .option(ChannelOption.SO_RCVBUF, config.getSocketBufferBytes())
                .option(ChannelOption.SO_SNDBUF, config.getSocketBufferBytes())
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(authHandler);
                    }
                });
        serverChannel = (DatagramChannel) bootstrap.bind(config.getPort()).sync().channel();

        // 后台定时：驱动 KCP 协议演进 + 空闲/认证超时清理
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kcp-reliable-maintenance");
            t.setDaemon(true);
            return t;
        });
        // 后台定时：驱动 KCP 协议演进 + 空闲/认证超时清理。
        // 节拍与协议重传间隔对齐，保证重传/窗口探测及时；清理按空闲时间判定，高频调用无副作用。
        scheduler.scheduleWithFixedDelay(authHandler::tickAndCleanup,
                config.getRetryIntervalMs(), config.getRetryIntervalMs(), TimeUnit.MILLISECONDS);

        log.info("KcpServer started on port {} (ioThreads={}, business={})",
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
        log.info("KcpServer stopped");
    }
}
