package com.game.gateway.kcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KCP 配置与最小生命周期装配测试：验证 @Value 手工构造的默认值、解析策略及会话承载。
 */
class KcpConfigTest {

    @Test
    void defaults_resolveCorrectly() {
        // 手工构造等价于 application.yml 中 game.kcp.* 的值
        KcpConfig cfg = new KcpConfig(2003, 32, 200, 60, 10, 1, 32, 64, 65536);
        assertEquals(2003, cfg.getPort());
        assertEquals(32, cfg.getWindow());
        assertEquals(200, cfg.getRetryIntervalMs());
        assertEquals(60, cfg.getSessionIdleTimeoutSeconds());
        assertEquals(10, cfg.getAuthTimeoutSeconds());
        assertEquals(1, cfg.getMaxConnectionsPerAccount());
        assertEquals(32, cfg.getWorkerThreads());
        assertEquals(64, cfg.getBusinessThreads());
        assertEquals(65536, cfg.getMaxFrameLength());
        // 显式线程数直接穿透
        assertEquals(32, cfg.resolveThreads());
        assertEquals(64, cfg.resolveBusinessThreads());
    }

    @Test
    void zeroThreads_usesFallback() {
        KcpConfig cfg = new KcpConfig(2003, 32, 200, 60, 10, 1, 0, 0, 65536);
        int cpus = Runtime.getRuntime().availableProcessors();
        assertEquals(Math.max(2, cpus), cfg.resolveThreads());
        assertEquals(Math.max(2, cpus * 2), cfg.resolveBusinessThreads());
    }

    @Test
    void session_carriesKcpAndAuthState() {
        KcpConfig cfg = new KcpConfig(2003, 32, 200, 60, 10, 1, 32, 64, 65536);
        // DatagramChannel 传 null 仅用于构造；本用例只验证会话状态/KCP 承载，不触发网络写出
        KcpSession session = new KcpSession(null, null, null, cfg);
        session.authenticateAs("channel-1");
        assertEquals("channel-1", session.channelId());
        assertEquals(true, session.isAuthenticated());
        assertEquals(true, session.isActive());
        assertEquals(0, session.kcp().getConv());
        session.updateNow(); // 驱动 KCP 演进不应抛异常
        assertEquals(null, session.recvDelivered());
        session.close();
    }
}