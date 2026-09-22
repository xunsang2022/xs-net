package com.game.gateway.netty;

import io.micrometer.core.instrument.Metrics;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * channelId ↔ {@link ClientEndpoint} 映射注册表。
 * 连接建立（认证通过）时注册，连接断开/会话过期时清理。
 * 同时兼容 TCP Channel（{@link TcpClientEndpoint}）与 UDP 会话（{@link UdpSession}）。
 * <p>同时向 Micrometer 注册在线连接数指标（gateway.online.connections）。</p>
 */
public final class ChannelRegistry {

    public static final ChannelRegistry INSTANCE = new ChannelRegistry();

    private static final String METRIC_ONLINE = "gateway.online.connections";
    private static final long REVOCATION_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final ConcurrentHashMap<String, ClientEndpoint> endpoints = new ConcurrentHashMap<>();
    /** 按 channelId 记录 JWT 吊销时间；JWT 本身是无状态的，不能从服务端直接删除。 */
    private final ConcurrentHashMap<String, Long> revokedAtMillis = new ConcurrentHashMap<>();
    /** 登录请求进入网关的入口时间戳（ns，按 sessionId/channelId），供 S2C 回包时计算网关内登录往返。 */
    private final ConcurrentHashMap<String, Long> loginIngressNanos = new ConcurrentHashMap<>();

    /** 记录一次登录请求进入网关的时间戳。 */
    public void recordLoginIngress(String channelId, long nanoTime) {
        if (channelId != null && !channelId.isBlank()) {
            loginIngressNanos.put(channelId, nanoTime);
        }
    }

    /** 取出并移除该 channelId 的登录入口时间戳；若无则返回 null。 */
    public Long takeLoginIngress(String channelId) {
        return channelId == null ? null : loginIngressNanos.remove(channelId);
    }

    private ChannelRegistry() {
        // 在线连接数 Gauge：随注册表大小实时变化
        Metrics.gauge(METRIC_ONLINE, endpoints, ConcurrentHashMap::size);
    }

    public void register(String channelId, ClientEndpoint endpoint) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        endpoints.put(channelId, endpoint);
    }

    public ClientEndpoint get(String channelId) {
        return channelId == null ? null : endpoints.get(channelId);
    }

    public ClientEndpoint remove(String channelId) {
        return channelId == null ? null : endpoints.remove(channelId);
    }

    public boolean remove(String channelId, ClientEndpoint expected) {
        return channelId != null && expected != null && endpoints.remove(channelId, expected);
    }

    public boolean isRegistered(String channelId) {
        return channelId != null && endpoints.containsKey(channelId);
    }

    /**
     * 该 channelId 是否存在且底层连接/会话依然活跃。
     * 用于单账号重复连接判定与 S2C 写前校验。
     */
    public boolean isActive(String channelId) {
        ClientEndpoint endpoint = get(channelId);
        return endpoint != null && endpoint.isActive();
    }

    public int size() {
        return endpoints.size();
    }

    /** 吊销当前 channelId 在指定时间点之前签发的 JWT。 */
    public void revokeToken(String channelId) {
        if (channelId != null && !channelId.isBlank()) {
            revokedAtMillis.put(channelId, System.currentTimeMillis());
        }
    }

    /** 判断指定签发时间的 JWT 是否已被吊销；新签发的同 channel token 可以继续使用。 */
    public boolean isTokenRevoked(String channelId, Date issuedAt) {
        Long revokedAt = activeRevocation(channelId);
        return revokedAt != null && (issuedAt == null || issuedAt.getTime() <= revokedAt);
    }

    void clearTokenRevocation(String channelId) {
        if (channelId != null) {
            revokedAtMillis.remove(channelId);
        }
    }

    private Long activeRevocation(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        Long revokedAt = revokedAtMillis.get(channelId);
        if (revokedAt != null && System.currentTimeMillis() - revokedAt >= REVOCATION_RETENTION_MILLIS) {
            revokedAtMillis.remove(channelId, revokedAt);
            return null;
        }
        return revokedAt;
    }
}
