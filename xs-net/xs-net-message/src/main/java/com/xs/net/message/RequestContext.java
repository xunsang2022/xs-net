package com.xs.net.message;

import com.xs.net.message.pb.NetMessage;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * 请求上下文记录类，封装RPC请求的元数据信息。
 * 包含链路追踪ID、超时时间、跳转次数、玩家和频道标识等，用于在网关、Actor和远程调用间传递。
 *
 * @param traceId 链路追踪ID
 * @param deadlineEpochMs 截止时间戳（毫秒）
 * @param hopCount 跳转次数
 * @param playerId 玩家ID
 * @param channelId 频道ID
 * @param sourceNode 源节点标识
 * @param schemaVersion 协议版本号
 */
public record RequestContext(
        String traceId,
        long deadlineEpochMs,
        int hopCount,
        String playerId,
        String channelId,
        String sourceNode,
        int schemaVersion
) {
    /** 当前协议版本号 */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** 旧版本默认超时时间（毫秒） */
    public static final long LEGACY_DEFAULT_TIMEOUT_MS = 3_000L;

    public RequestContext {
        traceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        if (deadlineEpochMs < 0) {
            throw new IllegalArgumentException("deadlineEpochMs must not be negative");
        }
        if (hopCount < 0) {
            throw new IllegalArgumentException("hopCount must not be negative");
        }
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
    }

    /**
     * 创建根请求上下文。
     *
     * @param playerId 玩家ID
     * @param channelId 频道ID
     * @param timeout 超时时间
     * @return 请求上下文
     */
    public static RequestContext root(String playerId, String channelId, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.currentTimeMillis() + Math.max(1L, timeout.toMillis());
        return new RequestContext(UUID.randomUUID().toString(), deadline, 0,
                playerId, channelId, null, CURRENT_SCHEMA_VERSION);
    }

    /**
     * 从网络消息中解析请求上下文。
     *
     * @param message 网络消息
     * @return 请求上下文
     */
    public static RequestContext from(NetMessage message) {
        Objects.requireNonNull(message, "message");
        String playerId = message.getPlayerId();
        if (playerId.isBlank() && message.getUserId() != 0) {
            playerId = String.valueOf(message.getUserId());
        }
        String channelId = message.getChannelId();
        if (channelId.isBlank() && message.getSessionId() != 0) {
            channelId = String.valueOf(message.getSessionId());
        }
        long deadline = message.getDeadlineEpochMs() > 0
                ? message.getDeadlineEpochMs()
                : System.currentTimeMillis() + LEGACY_DEFAULT_TIMEOUT_MS;
        return new RequestContext(
                message.getTraceId(),
                deadline,
                message.getHopCount(),
                playerId,
                channelId,
                message.getSourceNode(),
                message.getSchemaVersion());
    }

    /**
     * 创建下一跳的上下文，增加跳转次数并更新源节点。
     *
     * @param nextSourceNode 下一个源节点标识
     * @return 新的请求上下文
     */
    public RequestContext nextHop(String nextSourceNode) {
        return new RequestContext(traceId, deadlineEpochMs, hopCount + 1,
                playerId, channelId, nextSourceNode, schemaVersion);
    }

    /**
     * 创建带有新身份标识的上下文。
     *
     * @param nextPlayerId 下一个玩家ID
     * @param nextChannelId 下一个频道ID
     * @return 新的请求上下文
     */
    public RequestContext withIdentity(String nextPlayerId, String nextChannelId) {
        return new RequestContext(traceId, deadlineEpochMs, hopCount,
                nextPlayerId, nextChannelId, sourceNode, schemaVersion);
    }

    /**
     * 检查请求是否已超时。
     *
     * @return 如果已超时返回true，否则返回false
     */
    public boolean expired() {
        return deadlineEpochMs > 0 && System.currentTimeMillis() >= deadlineEpochMs;
    }

    /**
     * 将上下文信息应用到网络消息构建器。
     *
     * @param builder 消息构建器
     * @return 更新后的构建器
     */
    public NetMessage.Builder applyTo(NetMessage.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        builder.setTraceId(traceId)
                .setDeadlineEpochMs(deadlineEpochMs)
                .setHopCount(hopCount)
                .setSchemaVersion(schemaVersion);
        if (playerId != null && !playerId.isBlank()) {
            builder.setPlayerId(playerId);
        }
        if (channelId != null && !channelId.isBlank()) {
            builder.setChannelId(channelId);
        }
        if (sourceNode != null && !sourceNode.isBlank()) {
            builder.setSourceNode(sourceNode);
        }
        return builder;
    }
}
