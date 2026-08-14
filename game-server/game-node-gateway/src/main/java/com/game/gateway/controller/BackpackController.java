package com.game.gateway.controller;

import com.game.comment.util.JwtUtil;
import com.xs.core.akka.AkkaKit;
import com.game.message.action.CS_BACKPACK_INFO;
import com.game.message.action.CS_USE_ITEM;
import com.game.message.actor.logic.C2S;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/backpack")
public class BackpackController {

    @Resource
    private JwtUtil jwtUtil;

    @PostMapping("/info")
    public ResponseEntity<?> getBackpackInfo(
            @RequestHeader String Authorization
    ) {
        String playerId = jwtUtil.getPlayerId(Authorization);
        log.info("CS_BACKPACK_INFO: playerId={}", playerId);
        AkkaKit.tell(C2S.class, new CS_BACKPACK_INFO(jwtUtil.getChannelId(Authorization)), playerId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/use")
    public ResponseEntity<?> useItem(@RequestBody CS_USE_ITEM request,
                                     HttpServletRequest httpRequest) {
        String playerId = (String) httpRequest.getAttribute("playerId");
        String channelId = (String) httpRequest.getAttribute("channelId");
        request.setSessionId(channelId);
        log.info("CS_USE_ITEM: playerId={}, itemId={}, count={}", playerId, request.getItemId(), request.getCount());
        AkkaKit.tell(C2S.class, request, playerId);
        return ResponseEntity.ok().build();
    }
}
