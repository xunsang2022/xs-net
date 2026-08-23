package com.game.client;

import com.game.protocol.pb.SC_Logout;

import java.time.Duration;

public final class GameClientApplication {

    private GameClientApplication() {
    }

    public static void main(String[] args) throws Exception {
        ClientSettings settings = ClientSettings.from(args);
        try (GameTestClient client = new GameTestClient(settings)) {
            LoginSession session = client.createPlayer();
            client.connect(session.token());
            SC_Logout logout = client.logout(session);
            System.out.printf(
                    "login and logout completed: playerId=%s, playerName=%s, logoutPlayerId=%s%n",
                    session.playerId(),
                    session.playerName(),
                    logout.getPlayerId());
        }
    }

    record ClientSettings(
            String gatewayBaseUrl,
            String nettyHost,
            int nettyPort,
            long playerId,
            String playerName,
            Duration timeout
    ) {
        static ClientSettings from(String[] args) {
            String gatewayBaseUrl = "http://127.0.0.1:8080";
            String nettyHost = "127.0.0.1";
            int nettyPort = 8082;
            long playerId = System.currentTimeMillis();
            String playerName = "game-client";
            for (String arg : args) {
                String[] pair = arg.split("=", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("Invalid argument: " + arg);
                }
                switch (pair[0]) {
                    case "--gateway" -> gatewayBaseUrl = pair[1];
                    case "--netty-host" -> nettyHost = pair[1];
                    case "--netty-port" -> nettyPort = Integer.parseInt(pair[1]);
                    case "--player-id" -> playerId = Long.parseLong(pair[1]);
                    case "--player-name" -> playerName = pair[1];
                    default -> throw new IllegalArgumentException("Unknown argument: " + pair[0]);
                }
            }
            return new ClientSettings(gatewayBaseUrl, nettyHost, nettyPort, playerId, playerName, Duration.ofSeconds(10));
        }
    }
}
