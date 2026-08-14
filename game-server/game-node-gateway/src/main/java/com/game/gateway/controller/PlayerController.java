package com.game.gateway.controller;

import com.game.message.actor.logic.PlayerMessage;
import com.game.message.dto.LoginRequest;
import com.game.message.dto.LoginResponse;
import com.game.message.dto.LogoutRequest;
import com.game.message.dto.TokenResponse;
import com.game.message.rpc.GameRpcMessages;
import com.game.protocol.pb.CS_Logout;
import com.game.protocol.pb.PlayerEnter;
import com.xs.core.akka.AkkaKit;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private static final String CHANNEL_ID_HEADER = "X-Channel-Id";

    private final RestClient loginRestClient;

    @PostMapping("/create")
    public ResponseEntity<?> createPlayer(
            @RequestBody LoginRequest request
    ) {
        log.info("Received request: {}", request);

        String channelId = UUID.randomUUID().toString();
        TokenResponse auth;
        try {
            auth = loginRestClient.post()
                    .uri("/api/auth/issue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CHANNEL_ID_HEADER, channelId)
                    .body(request)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            log.error("Failed to issue auth token: playerId={}", request.getPlayerId(), e);
            return ResponseEntity.status(502).build();
        }

        if (auth == null || auth.getToken() == null) {
            log.error("Auth service returned empty token: playerId={}", request.getPlayerId());
            return ResponseEntity.status(502).build();
        }

        PlayerEnter.Builder enter = PlayerEnter.newBuilder().setPlayerId(request.getPlayerId());
        if (request.getPlayerName() != null) {
            enter.setPlayerName(request.getPlayerName());
        }
        enter.setChannelId(channelId);
        AkkaKit.tell(PlayerMessage.class, GameRpcMessages.wrap(enter.build()), String.valueOf(request.getPlayerId()));

        LoginResponse response = new LoginResponse(
                0, String.valueOf(request.getPlayerId()), request.getPlayerName(), auth.getToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestBody LogoutRequest request,
            HttpServletRequest httpRequest
    ) {
        log.info("SC_Logout request: {}", request);

        // 从 JWT Filter 注入的 channelId 路由登出消息，确保 logic 节点能通过 S2C 回传给正确 ChannelActor
        String playerId = (String) httpRequest.getAttribute("playerId");
        String channelId = (String) httpRequest.getAttribute("channelId");
        request.setSessionId(channelId);
        CS_Logout.Builder logout = CS_Logout.newBuilder();
        if (request.getPlayerId() != null) {
            logout.setPlayerId(request.getPlayerId());
        }
        if (request.getSessionId() != null) {
            logout.setSessionId(request.getSessionId());
        }
        AkkaKit.tell(PlayerMessage.class, GameRpcMessages.wrap(logout.build()), playerId);

        return ResponseEntity.ok().build();
    }
}
