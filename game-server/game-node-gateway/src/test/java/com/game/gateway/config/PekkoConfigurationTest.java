package com.game.gateway.config;

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
        assertEquals("pekko://game-system@gateway-1:2001",
                cluster.getStringList("pekko.cluster.seed-nodes").getFirst());
    }
}
