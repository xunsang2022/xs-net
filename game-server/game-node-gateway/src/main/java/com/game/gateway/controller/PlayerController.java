package com.game.gateway.controller;

import com.xs.core.akka.AkkaKit;
import com.game.comment.util.JwtUtil;
import com.game.message.action.CS_LOGIN;
import com.game.message.action.CS_Logout;
import com.game.message.action.SC_LOGIN;
import com.game.message.actor.login.PlayerMessage;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;


@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final JwtUtil jwtUtil;

    @PostMapping("/create")
    public ResponseEntity<?> createPlayer(
            @RequestBody CS_LOGIN request
    ) {
        log.info("Received request: {}", request);

        // channelId 作为 ChannelActor 实体 ID，Login/Logic 节点通过它路由 S2C 消息回客户端
        String channelId = UUID.randomUUID().toString();
        request.setSessionId(channelId);

        // 异步通知 Login 节点初始化用户数据
        AkkaKit.tell(PlayerMessage.class, request, request.getPlayerName());

        // 同步生成 JWT token（含 playerId + channelId）
        String token = jwtUtil.generateToken(request.getPlayerId(), channelId);

        SC_LOGIN response = new SC_LOGIN(0, request.getPlayerId(), request.getPlayerName(), token);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestBody CS_Logout request,
            HttpServletRequest httpRequest
    ) {
        log.info("SC_Logout request: {}", request);

        // 从 JWT Filter 注入的 channelId 路由登出消息，确保 Login 节点能通过 S2C 回传给正确 ChannelActor
        String playerId = (String) httpRequest.getAttribute("playerId");
        String channelId = (String) httpRequest.getAttribute("channelId");
        request.setSessionId(channelId);
        AkkaKit.tell(PlayerMessage.class, request, playerId);

        return ResponseEntity.ok().build();
    }
}
