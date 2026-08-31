package com.xs.core.akka;

import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AkkaKitTest {

    @Test
    void clusterShardingCanBeRegisteredAndRead() {
        ClusterSharding sharding = mock(ClusterSharding.class);
        AkkaKit.setClusterSharding(sharding);
        assertSame(sharding, AkkaKit.getClusterSharding());
    }

    @Test
    void clusterShardingThrowsBeforeInitialization() {
        AkkaKit.setClusterSharding(null);
        assertThrows(IllegalStateException.class, AkkaKit::getClusterSharding);
    }
}
