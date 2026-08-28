package com.game.gateway.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyConfigTest {

    /** 显式配置优先；0 表示取默认 2*CPU。 */
    @Test
    void resolveThreadsRespectsExplicitValue() {
        NettyConfig config = nettyConfigWithBusinessThreads(4);
        assertEquals(4, config.resolveThreads(config.getBusinessThreads()));
    }

    @Test
    void resolveThreadsZeroFallsBackToCpu() {
        NettyConfig config = nettyConfigWithBusinessThreads(0);
        int expected = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        assertEquals(expected, config.resolveThreads(config.getBusinessThreads()));
    }

    private NettyConfig nettyConfigWithBusinessThreads(int businessThreads) {
        return new NettyConfig(
                8082, 1, 0, businessThreads, 1024, 65536, 10, 1, 300, 32768, 65536);
    }
}