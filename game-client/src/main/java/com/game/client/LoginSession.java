package com.game.client;

public record LoginSession(long playerId, String playerName, String token) {
}
