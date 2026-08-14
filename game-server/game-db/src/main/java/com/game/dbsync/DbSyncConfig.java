package com.game.dbsync;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DbSyncProperties.class)
public class DbSyncConfig {
}
