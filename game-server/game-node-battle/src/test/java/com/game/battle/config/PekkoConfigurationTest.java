package com.game.battle.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PekkoConfigurationTest {

    @Test
    void usesPekkoForDefaultAndClusterConfiguration() {
        Config defaults = ConfigFactory.parseResources("application.conf").resolve();

        assertEquals("cluster", defaults.getString("pekko.actor.provider"));
        assertEquals("thread-pool-executor", defaults.getString("pekko.actor.battle-control-dispatcher.executor"));
        assertEquals(4, defaults.getInt("pekko.actor.battle-control-dispatcher.thread-pool-executor.fixed-pool-size"));
        assertEquals(1, defaults.getInt("pekko.actor.battle-control-dispatcher.throughput"));
        assertEquals("battle", defaults.getStringList("pekko.cluster.roles").getFirst());
        assertEquals(4501, defaults.getInt("pekko.remote.artery.canonical.port"));
        assertEquals("org.apache.pekko.cluster.ddata.DurableStore$None$",
                defaults.getString("pekko.distributed-data.durable.store"));
    }
}
