package com.game.login.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于客户端 IP 的内存固定窗口限流器，无第三方依赖。
 * 仅用于单实例的基础防护，多实例部署时各节点独立计数，不做全局限流。
 */
public class ClientIpFixedWindowRateLimiter {

    /** 允许跟踪的最大 IP 数量，超过后清理过期窗口，避免内存无限增长。 */
    private static final int MAX_TRACKED_IPS = 100_000;

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ClientIpFixedWindowRateLimiter(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * 尝试获取一次许可。
     *
     * @param clientIp 客户端 IP，为空时归入 "unknown" 桶
     * @return 当前窗口内未超出配额返回 true，否则返回 false
     */
    public boolean tryAcquire(String clientIp) {
        long now = System.currentTimeMillis();
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startMillis >= windowMillis) {
                return new Window(now, new AtomicInteger(1));
            }
            current.count.incrementAndGet();
            return current;
        });
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.values().removeIf(exist -> now - exist.startMillis >= windowMillis);
        }
        return window.count.get() <= maxRequests;
    }

    /** 单个 IP 的固定窗口计数。 */
    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count;

        private Window(long startMillis, AtomicInteger count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }
}
