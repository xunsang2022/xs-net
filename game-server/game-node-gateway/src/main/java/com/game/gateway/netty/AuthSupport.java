package com.game.gateway.netty;

import com.game.comment.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.core.instrument.Metrics;

import java.nio.charset.StandardCharsets;

/**
 * 客户端握手认证的传输无关公共流程与指标名常量。
 * <p>TCP（{@link AuthHandler}）、UDP（{@link UdpAuthHandler}）、KCP
 * （{@code com.game.gateway.kcp.KcpAuthHandler}）三处 authenticate 的主干一致：
 * {@link #resolve} 解析 JWT 并取 channelId → 单账号并发校验 → {@link ChannelRegistry} 注册 → 指标计数。
 * 本类承载该主干（鉴别、并发校验、计数、登录入口打点）与各传输指标名常量；
 * 各 Handler 只保留握手回执、会话创建/清理等传输特化逻辑。</p>
 */
public final class AuthSupport {

    private AuthSupport() {
    }

    /** 认证失败分类，供各 Handler 决定日志与清理动作。 */
    public enum Failure {
        /** JWT 校验失败。 */
        INVALID_TOKEN,
        /** token 中缺少 channelId。 */
        MISSING_CHANNEL_ID
    }

    /** 认证鉴别结果：成功时 {@code channelId} 非空，失败时 {@code failure} 非空。 */
    public record Result(String channelId, Failure failure) {
        public boolean success() {
            return failure == null;
        }
    }

    /** 单个传输的认证/上行指标名集合（集中声明，避免三个 Handler 各写一遍）。 */
    public record MetricNames(String authSuccess, String authFailure, String c2sMessages) {
    }

    /** TCP 指标名。 */
    public static final MetricNames TCP_METRICS =
            new MetricNames("gateway.auth.success", "gateway.auth.failure", "gateway.c2s.messages");
    /** UDP 指标名。 */
    public static final MetricNames UDP_METRICS =
            new MetricNames("gateway.udp.auth.success", "gateway.udp.auth.failure", "gateway.udp.c2s.messages");
    /** KCP 指标名。 */
    public static final MetricNames KCP_METRICS =
            new MetricNames("gateway.kcp.auth.success", "gateway.kcp.auth.failure", "gateway.kcp.c2s.messages");

    /**
     * 校验 JWT 载荷并提取 channelId；仅做鉴别，不产生日志/指标/注册副作用，
     * 失败细节由调用方按传输差异处理。等价于原先各 Handler 手工执行的
     * 解码 token → {@link JwtUtil#validateToken} → {@link JwtUtil#getChannelId}。
     */
    public static Result resolve(JwtUtil jwtUtil, byte[] tokenBytes) {
        Claims claims = jwtUtil.validateToken(new String(tokenBytes, StandardCharsets.UTF_8));
        if (claims == null) {
            return new Result(null, Failure.INVALID_TOKEN);
        }
        String channelId = jwtUtil.getChannelId(claims);
        if (channelId == null || channelId.isBlank()) {
            return new Result(null, Failure.MISSING_CHANNEL_ID);
        }
        return new Result(channelId, null);
    }

    /**
     * TCP/UDP 共用的单账号并发校验：同 channelId 已有活跃连接时，若为本逻辑连接的重连
     * （connectionRandom 相同）则替换旧的，否则判定为重复连接。
     *
     * @return true 表示校验通过、可继续注册；false 表示应拒绝本次认证
     */
    public static boolean admitReconnect(String channelId, long connectionRandom, int maxConnectionsPerAccount) {
        ClientEndpoint existing = ChannelRegistry.INSTANCE.get(channelId);
        if (maxConnectionsPerAccount > 0 && existing != null && existing.isActive()) {
            if (existing.connectionRandom() != connectionRandom) {
                return false;
            }
            ChannelRegistry.INSTANCE.remove(channelId, existing);
            existing.close();
        }
        return true;
    }

    /** 记录认证成功计数。 */
    public static void countSuccess(MetricNames metrics) {
        Metrics.counter(metrics.authSuccess()).increment();
    }

    /** 记录认证失败计数。 */
    public static void countFailure(MetricNames metrics) {
        Metrics.counter(metrics.authFailure()).increment();
    }

    /** 记录上行 C2S 消息计数。 */
    public static void countC2S(MetricNames metrics) {
        Metrics.counter(metrics.c2sMessages()).increment();
    }

    /** 记录登录请求进入网关的时间戳（认证成功时调用），供首次 S2C 回包计算网关内登录往返。 */
    public static void markLoginIngress(String channelId) {
        ChannelRegistry.INSTANCE.recordLoginIngress(channelId, System.nanoTime());
    }
}
