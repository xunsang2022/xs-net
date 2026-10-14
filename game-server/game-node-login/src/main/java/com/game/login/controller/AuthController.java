package com.game.login.controller;

import com.game.comment.util.JwtUtil;
import com.game.login.ratelimit.ClientIpFixedWindowRateLimiter;
import com.game.message.dto.LoginRequest;
import com.game.message.dto.TokenResponse;
import com.game.message.dto.TokenVerifyResponse;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String CHANNEL_ID_HEADER = "X-Channel-Id";
    private static final int MAX_CHANNEL_ID_LENGTH = 64;
    /** 签发接口限流：单 IP 每个窗口允许的最大请求数。 */
    static final int ISSUE_MAX_REQUESTS = 20;
    /** 签发接口限流窗口长度（毫秒）。 */
    static final long ISSUE_WINDOW_MILLIS = 60_000L;

    private final JwtUtil jwtUtil;

    /** 按客户端 IP 维度的内存固定窗口限流器，避免签发接口被滥用。 */
    private final ClientIpFixedWindowRateLimiter issueRateLimiter =
            new ClientIpFixedWindowRateLimiter(ISSUE_MAX_REQUESTS, ISSUE_WINDOW_MILLIS);

    @PostMapping("/issue")
    public ResponseEntity<TokenResponse> issue(
            @RequestBody(required = false) LoginRequest request,
            @RequestHeader(value = CHANNEL_ID_HEADER, required = false) String channelId,
            HttpServletRequest httpRequest
    ) {
        // 限流：按客户端 IP 维度，超出配额返回 429
        if (!issueRateLimiter.tryAcquire(resolveClientIp(httpRequest))) {
            log.warn("issue token rate limited, clientIp={}", resolveClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        // 最小校验：请求体缺失或 playerId 非法时返回 400
        if (request == null || request.getPlayerId() == null || request.getPlayerId() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String channel = sanitizeChannelId(channelId);
        if (channel == null) {
            channel = UUID.randomUUID().toString();
        }
        String token = jwtUtil.generateToken(String.valueOf(request.getPlayerId()), channel);
        long expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationMillis();
        return ResponseEntity.ok(new TokenResponse(token, expiresAt));
    }

    @PostMapping("/verify")
    public ResponseEntity<TokenVerifyResponse> verify(@RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        Claims claims = token == null ? null : jwtUtil.validateToken(token);
        if (claims == null) {
            return ResponseEntity.ok(new TokenVerifyResponse(false, null, null));
        }
        String playerId = jwtUtil.getPlayerId(claims);
        try {
            return ResponseEntity.ok(new TokenVerifyResponse(
                    true,
                    playerId == null ? null : Long.parseLong(playerId),
                    jwtUtil.getChannelId(claims)));
        } catch (NumberFormatException e) {
            log.warn("Invalid playerId claim in token");
            return ResponseEntity.ok(new TokenVerifyResponse(false, null, null));
        }
    }

    /** 取客户端 IP，取不到时返回 null，由限流器归入 unknown 桶。 */
    private static String resolveClientIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    /**
     * 清洗渠道号：去除 ISO 控制字符并截断超长内容，空值返回 null 交由调用方生成默认值。
     */
    static String sanitizeChannelId(String channelId) {
        if (channelId == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(channelId.length());
        channelId.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .forEach(normalized::appendCodePoint);
        String result = normalized.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        return result.length() > MAX_CHANNEL_ID_LENGTH ? result.substring(0, MAX_CHANNEL_ID_LENGTH) : result;
    }
}
