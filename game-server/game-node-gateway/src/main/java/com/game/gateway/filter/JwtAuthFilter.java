package com.game.gateway.filter;

import com.game.comment.util.JwtUtil;
import com.game.message.dto.TokenVerifyResponse;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/players/create"
    );

    /** verifyRemote 本地缓存 TTL（毫秒），避免同一 token 反复触发远程验签 RTT */
    private static final long REMOTE_CACHE_TTL_MILLIS = 60_000L;
    /** 缓存条目上限，超过后先清理过期条目再写入 */
    private static final int REMOTE_CACHE_MAX_SIZE = 10_000;

    private final JwtUtil jwtUtil;
    private final RestClient loginRestClient;
    private final String verifyMode;
    private final ConcurrentHashMap<String, CacheEntry> remoteVerifyCache = new ConcurrentHashMap<>();

    public JwtAuthFilter(
            JwtUtil jwtUtil,
            RestClient loginRestClient,
            @Value("${game.auth.verify-mode:local}") String verifyMode
    ) {
        this.jwtUtil = jwtUtil;
        this.loginRestClient = loginRestClient;
        this.verifyMode = verifyMode;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return WHITELIST.contains(path) || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid Authorization header");
            return;
        }

        boolean valid = "remote".equalsIgnoreCase(verifyMode)
                ? verifyRemote(authHeader, request, response)
                : verifyLocal(authHeader, request, response);
        if (valid) {
            chain.doFilter(request, response);
        }
    }

    private boolean verifyLocal(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Claims claims = jwtUtil.validateToken(token);
        if (claims == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return false;
        }
        injectAttributes(request, jwtUtil.getPlayerId(claims), jwtUtil.getChannelId(claims));
        return true;
    }

    private boolean verifyRemote(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        CacheEntry cached = remoteVerifyCache.get(token);
        if (cached != null) {
            if (cached.expireAt > System.currentTimeMillis()) {
                injectAttributes(request, String.valueOf(cached.response.getPlayerId()), cached.response.getChannelId());
                return true;
            }
            remoteVerifyCache.remove(token, cached);
        }
        try {
            ResponseEntity<TokenVerifyResponse> entity = loginRestClient.post()
                    .uri("/api/auth/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", token))
                    .retrieve()
                    .toEntity(TokenVerifyResponse.class);
            TokenVerifyResponse result = entity.getBody();
            if (result == null || !result.isValid()
                    || result.getPlayerId() == null || result.getChannelId() == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return false;
            }
            if (remoteVerifyCache.size() >= REMOTE_CACHE_MAX_SIZE) {
                remoteVerifyCache.entrySet().removeIf(e -> e.getValue().expireAt <= System.currentTimeMillis());
            }
            remoteVerifyCache.put(token, new CacheEntry(result, System.currentTimeMillis() + REMOTE_CACHE_TTL_MILLIS));
            injectAttributes(request, String.valueOf(result.getPlayerId()), result.getChannelId());
            return true;
        } catch (RestClientException e) {
            log.warn("Remote auth verification failed: {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Auth service unavailable");
            return false;
        }
    }

    private void injectAttributes(HttpServletRequest request, String playerId, String channelId) {
        request.setAttribute("playerId", playerId);
        request.setAttribute("channelId", channelId);
    }

    /** verifyRemote 缓存条目：仅缓存校验通过的结果 */
    private static final class CacheEntry {
        final TokenVerifyResponse response;
        final long expireAt;

        CacheEntry(TokenVerifyResponse response, long expireAt) {
            this.response = response;
            this.expireAt = expireAt;
        }
    }
}
