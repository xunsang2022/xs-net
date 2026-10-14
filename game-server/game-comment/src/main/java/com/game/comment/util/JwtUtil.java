package com.game.comment.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
public class JwtUtil {

    private static final String CLAIM_CHANNEL_ID = "cid";

    private final SecretKey signingKey;
    private final JwtParser parser;
    private final long expiration;

    /**
     * @param secret     JWT 签名密钥（至少 32 字节）
     * @param expiration token 有效期（毫秒）
     */
    public JwtUtil(String secret, long expiration) {
        // 快速失败：不使用弱默认密钥。空密钥或长度不足 32 字节（HS256 最小 256 位）直接拒绝启动
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be blank; configure jwt.secret / JWT_SECRET");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (256-bit) for HS256, actual=" + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        // JwtParser 是线程安全的，可复用，避免每次校验都重新构建
        this.parser = Jwts.parser().verifyWith(signingKey).build();
        this.expiration = expiration;
    }

    /**
     * 生成 JWT token
     *
     * @param playerId  玩家 ID
     * @param channelId ChannelActor 实体 ID，用于 S2C 消息路由回客户端
     */
    public String generateToken(String playerId, String channelId) {
        return Jwts.builder()
                .subject(playerId)
                .claim(CLAIM_CHANNEL_ID, channelId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(signingKey)
                .compact();
    }

    public long getExpirationMillis() {
        return expiration;
    }

    /**
     * 验证并解析 token，失败返回 null
     */
    public Claims validateToken(String token) {
        try {
            return parser.parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }

    public String getPlayerId(Claims claims) {
        return claims.getSubject();
    }

    public String getChannelId(Claims claims) {
        return claims.get(CLAIM_CHANNEL_ID, String.class);
    }
}
