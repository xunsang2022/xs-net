package com.game.client;

import java.time.Duration;

public final class GameClientApplication {

    private GameClientApplication() {
    }

    public static void main(String[] args) throws Exception {
        ClientSettings settings = ClientSettings.from(args);
        try (ClientHttpServer server = new ClientHttpServer(settings)) {
            server.start();
            System.out.printf("client HTTP server started: http://%s:%s%n", settings.httpHost(), server.port());
            Thread.currentThread().join();
        }
    }

    record ClientSettings(
            String httpHost,
            int httpPort,
            String nettyHost,
            int nettyPort,
            Duration timeout
    ) {
        static ClientSettings from(String[] args) {
            String httpHost = "127.0.0.1";
            int httpPort = 8080;
            String nettyHost = "127.0.0.1";
            int nettyPort = 8082;
            for (String arg : args) {
                String[] pair = arg.split("=", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("Invalid argument: " + arg);
                }
                switch (pair[0]) {
                    case "--http-host" -> httpHost = pair[1];
                    case "--http-port" -> httpPort = Integer.parseInt(pair[1]);
                    case "--netty-host" -> nettyHost = pair[1];
                    case "--netty-port" -> nettyPort = Integer.parseInt(pair[1]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + pair[0]);
                }
            }
            return new ClientSettings(httpHost, httpPort, nettyHost, nettyPort, Duration.ofSeconds(10));
        }
    }
}
