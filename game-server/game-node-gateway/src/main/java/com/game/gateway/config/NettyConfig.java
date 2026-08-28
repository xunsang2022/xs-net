package com.game.gateway.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Netty 服务器调优参数（对应 application.yml 的 game.netty.*）。
 */
@Data
@Component
public class NettyConfig {

    private final int port;
    private final int bossThreads;
    private final int workerThreads;
    private final int businessThreads;
    private final int backlog;
    private final int maxFrameLength;
    private final int authTimeoutSeconds;
    private final int maxConnectionsPerAccount;
    private final int readerIdleTimeoutSeconds;
    private final int writeBufferLowWaterMark;
    private final int writeBufferHighWaterMark;

    public NettyConfig(
            @Value("${game.netty.port:8082}") int port,
            @Value("${game.netty.boss-threads:1}") int bossThreads,
            @Value("${game.netty.worker-threads:0}") int workerThreads,
            @Value("${game.netty.business-threads:0}") int businessThreads,
            @Value("${game.netty.backlog:1024}") int backlog,
            @Value("${game.netty.max-frame-length:65536}") int maxFrameLength,
            @Value("${game.netty.auth-timeout-seconds:10}") int authTimeoutSeconds,
            @Value("${game.netty.max-connections-per-account:1}") int maxConnectionsPerAccount,
            @Value("${game.netty.reader-idle-timeout-seconds:300}") int readerIdleTimeoutSeconds,
            @Value("${game.netty.write-buffer-low-water-mark:32768}") int writeBufferLowWaterMark,
            @Value("${game.netty.write-buffer-high-water-mark:65536}") int writeBufferHighWaterMark) {
        this.port = port;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
        this.businessThreads = businessThreads;
        this.backlog = backlog;
        this.maxFrameLength = maxFrameLength;
        this.authTimeoutSeconds = authTimeoutSeconds;
        this.maxConnectionsPerAccount = maxConnectionsPerAccount;
        this.readerIdleTimeoutSeconds = readerIdleTimeoutSeconds;
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
    }

    /** 解析线程数：0 表示采用默认策略（2 * CPU 核数）。 */
    public int resolveThreads(int configured) {
        return configured > 0 ? configured : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    }
}