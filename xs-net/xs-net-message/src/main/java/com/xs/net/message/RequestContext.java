package com.xs.net.message;

import com.xs.net.message.pb.NetMessage;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Immutable request metadata propagated through gateway, actors and remote calls. */
public record RequestContext(
        String traceId,
        long deadlineEpochMs,
        int hopCount,
        String playerId,
        String channelId,
        String sourceNode,
        int schemaVersion
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
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

    public static RequestContext root(String playerId, String channelId, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.currentTimeMillis() + Math.max(1L, timeout.toMillis());
        return new RequestContext(UUID.randomUUID().toString(), deadline, 0,
                playerId, channelId, null, CURRENT_SCHEMA_VERSION);
    }

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

    public RequestContext nextHop(String nextSourceNode) {
        return new RequestContext(traceId, deadlineEpochMs, hopCount + 1,
                playerId, channelId, nextSourceNode, schemaVersion);
    }

    public RequestContext withIdentity(String nextPlayerId, String nextChannelId) {
        return new RequestContext(traceId, deadlineEpochMs, hopCount,
                nextPlayerId, nextChannelId, sourceNode, schemaVersion);
    }

    public boolean expired() {
        return deadlineEpochMs > 0 && System.currentTimeMillis() >= deadlineEpochMs;
    }

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
