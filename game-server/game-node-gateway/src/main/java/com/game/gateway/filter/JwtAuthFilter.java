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

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/players/create"
    );

    private final JwtUtil jwtUtil;
    private final RestClient loginRestClient;
    private final String verifyMode;

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
}
