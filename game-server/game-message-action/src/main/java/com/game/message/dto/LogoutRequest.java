package com.game.message.dto;

import lombok.Data;

@Data
public class LogoutRequest {
    private String playerId;
    private String sessionId;
}
