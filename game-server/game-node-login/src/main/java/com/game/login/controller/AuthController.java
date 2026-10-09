package com.game.login.controller;

import com.game.comment.util.JwtUtil;
import com.game.message.dto.LoginRequest;
import com.game.message.dto.TokenResponse;
import com.game.message.dto.TokenVerifyResponse;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final JwtUtil jwtUtil;

    @PostMapping("/issue")
    public ResponseEntity<TokenResponse> issue(
            @RequestBody LoginRequest request,
            @RequestHeader(value = CHANNEL_ID_HEADER, required = false) String channelId
    ) {
        if (channelId == null || channelId.isBlank()) {
            channelId = UUID.randomUUID().toString();
        }
        String token = jwtUtil.generateToken(String.valueOf(request.getPlayerId()), channelId);
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
}
