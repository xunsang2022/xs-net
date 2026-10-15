package com.game.gateway.kcp;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KCP 服务器调优参数（对应 application.yml 的 game.kcp.*）。
 * <p>照 UdpConfig 采用手工 @Value 构造模式。</p>
 */
@Data
@Component
public class KcpConfig {

    private final int port;
    private final int window;
    private final long retryIntervalMs;
    private final int sessionIdleTimeoutSeconds;
    private final int authTimeoutSeconds;
    private final int maxConnectionsPerAccount;
    private final int workerThreads;
    private final int businessThreads;
    private final int maxFrameLength;
    private final int socketBufferBytes;
    private final int fecK;
    private final int fecM;

    public KcpConfig(
            @Value("${game.kcp.port:2003}") int port,
            @Value("${game.kcp.window:32}") int window,
            @Value("${game.kcp.retry-interval-ms:50}") long retryIntervalMs,
            @Value("${game.kcp.session-idle-timeout-seconds:60}") int sessionIdleTimeoutSeconds,
            @Value("${game.kcp.auth-timeout-seconds:10}") int authTimeoutSeconds,
            @Value("${game.kcp.max-connections-per-account:1}") int maxConnectionsPerAccount,
            @Value("${game.kcp.worker-threads:32}") int workerThreads,
            @Value("${game.kcp.business-threads:64}") int businessThreads,
            @Value("${game.kcp.max-frame-length:65536}") int maxFrameLength,
            @Value("${game.kcp.socket-buffer-bytes:4194304}") int socketBufferBytes,
            @Value("${game.kcp.fec-k:0}") int fecK,
            @Value("${game.kcp.fec-m:1}") int fecM) {
        this.port = port;
        this.window = window;
        this.retryIntervalMs = retryIntervalMs;
        this.sessionIdleTimeoutSeconds = sessionIdleTimeoutSeconds;
        this.authTimeoutSeconds = authTimeoutSeconds;
        this.maxConnectionsPerAccount = maxConnectionsPerAccount;
        this.workerThreads = workerThreads;
        this.businessThreads = businessThreads;
        this.maxFrameLength = maxFrameLength;
        this.socketBufferBytes = socketBufferBytes;
        this.fecK = fecK;
        this.fecM = fecM;
    }

    /** 解析 KCP I/O 线程数：0 表示采用默认策略（max(2, CPU 核数)）。 */
    public int resolveThreads() {
        return workerThreads > 0 ? workerThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** 解析 KCP 业务线程池大小：0 表示采用默认策略（2 * CPU 核数）。 */
    public int resolveBusinessThreads() {
        return businessThreads > 0 ? businessThreads : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    }
}