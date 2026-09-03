package com.game.logic.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PekkoConfigurationTest {

    @Test
    void usesPekkoForDefaultAndClusterConfiguration() {
        Config defaults = ConfigFactory.parseResources("application.conf").resolve();
        Config cluster = ConfigFactory.parseResources("application-cluster.conf").resolve();

        assertEquals("cluster", defaults.getString("pekko.actor.provider"));
        assertEquals("thread-pool-executor", defaults.getString("pekko.actor.battle-dispatcher.executor"));
        assertEquals(8, defaults.getInt("pekko.actor.battle-dispatcher.thread-pool-executor.fixed-pool-size"));
        assertEquals(1, defaults.getInt("pekko.actor.battle-dispatcher.throughput"));
        assertEquals("thread-pool-executor", defaults.getString("pekko.actor.player-dispatcher.executor"));
        assertEquals(8, defaults.getInt("pekko.actor.player-dispatcher.thread-pool-executor.fixed-pool-size"));
        assertEquals(1, defaults.getInt("pekko.actor.player-dispatcher.throughput"));
        assertEquals("org.apache.pekko.cluster.ddata.DurableStore$None$",
                defaults.getString("pekko.distributed-data.durable.store"));
        assertEquals("pekko://game-system@gateway-1:2001",
                cluster.getStringList("pekko.cluster.seed-nodes").getFirst());
    }
}
