package com.game.comment.util;

import io.jsonwebtoken.Claims;
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
    private final long expiration;

    /**
     * @param secret     JWT 签名密钥（至少 32 字节）
     * @param expiration token 有效期（毫秒）
     */
    public JwtUtil(String secret, long expiration) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
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

    /**
     * 验证并解析 token，失败返回 null
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }

    public String getPlayerId(Claims claims) {
        return claims.getSubject();
    }

    public String getPlayerId(String token) {
        Claims claims = this.validateToken(token);
        return claims == null ? null : getPlayerId(claims);
    }

    public String getChannelId(Claims claims) {
        return claims.get(CLAIM_CHANNEL_ID, String.class);
    }

    public String getChannelId(String token) {
        Claims claims = this.validateToken(token);
        return claims == null ? null : getChannelId(claims);
    }
}
