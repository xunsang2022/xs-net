package com.game.gateway.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * UDP 服务器调优参数（对应 application.yml 的 game.udp.*）。
 */
@Data
@Component
public class UdpConfig {

    private final int port;
    private final int window;
    private final long retryIntervalMs;
    private final int sessionIdleTimeoutSeconds;
    private final int authTimeoutSeconds;
    private final int maxConnectionsPerAccount;
    private final int maxFrameLength;
    private final int workerThreads;
    private final int businessThreads;
    private final int socketBufferBytes;

    public UdpConfig(
            @Value("${game.udp.port:2002}") int port,
            @Value("${game.udp.window:32}") int window,
            @Value("${game.udp.retry-interval-ms:200}") long retryIntervalMs,
            @Value("${game.udp.session-idle-timeout-seconds:60}") int sessionIdleTimeoutSeconds,
            @Value("${game.udp.auth-timeout-seconds:10}") int authTimeoutSeconds,
            @Value("${game.udp.max-connections-per-account:1}") int maxConnectionsPerAccount,
            @Value("${game.udp.max-frame-length:65536}") int maxFrameLength,
            @Value("${game.udp.worker-threads:0}") int workerThreads,
            @Value("${game.udp.business-threads:0}") int businessThreads,
            @Value("${game.udp.socket-buffer-bytes:4194304}") int socketBufferBytes) {
        this.port = port;
        this.window = window;
        this.retryIntervalMs = retryIntervalMs;
        this.sessionIdleTimeoutSeconds = sessionIdleTimeoutSeconds;
        this.authTimeoutSeconds = authTimeoutSeconds;
        this.maxConnectionsPerAccount = maxConnectionsPerAccount;
        this.maxFrameLength = maxFrameLength;
        this.workerThreads = workerThreads;
        this.businessThreads = businessThreads;
        this.socketBufferBytes = socketBufferBytes;
    }

    /** 解析 UDP I/O 线程数：0 表示采用默认策略（max(2, CPU 核数)）。 */
    public int resolveThreads() {
        return workerThreads > 0 ? workerThreads : Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** 解析 UDP 业务线程池大小：0 表示采用默认策略（2 * CPU 核数）。 */
    public int resolveBusinessThreads() {
        return businessThreads > 0 ? businessThreads : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
    }
}
