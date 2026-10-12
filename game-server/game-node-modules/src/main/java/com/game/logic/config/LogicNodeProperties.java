package com.game.logic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings owned by the game logic process. */
@Data
@ConfigurationProperties(prefix = "game")
public class LogicNodeProperties {

    private Storage storage = new Storage();
    private Io io = new Io();
    private Db db = new Db();
    private Management management = new Management();

    @Data
    public static class Storage {
        private int syncIntervalMs = 1000;
    }

    @Data
    public static class Io {
        private int redisThreads = 16;
        private int dbThreads = 8;
    }

    @Data
    public static class Db {
        private int batchSize = 100;
        private int bgsaveSeconds = 3;
        private int flushConcurrency = 4;
        private long logPollMs = 1000L;
    }

    @Data
    public static class Management {
        private long configReloadIntervalMs = 1000L;
        private String token = "";
        private Arthas arthas = new Arthas();
        private Metrics metrics = new Metrics();
    }

    @Data
    public static class Arthas {
        private String home = "";
        private int telnetPort = 36581;
    }

    @Data
    public static class Metrics {
        private String host = "127.0.0.1";
        private int port = 9090;
    }
}
