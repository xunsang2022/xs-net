package com.game.logic.config;

import com.game.eventsourcing.sync.DBSyncConfig;
import com.game.eventsourcing.sync.DBSyncEngine;
import com.game.eventsourcing.sync.DBSyncEngineRegistry;
import com.game.eventsourcing.sync.EntityPersistenceRouter;
import com.game.eventsourcing.log.LogConsumer;
import com.game.eventsourcing.log.LogEventMapper;
import com.game.eventsourcing.persistence.MybatisPersistenceConfiguration;
import com.game.logic.actor.player.db.PlayerMapper;
import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.redis.RedisStore;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LogicNodeProperties.class)
public class LogicNodeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        JacksonJsonRedisSerializer<Object> jsonSerializer = new JacksonJsonRedisSerializer<>(Object.class);
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        return template;
    }

    @Bean
    public RedisStore redisStore(StringRedisTemplate redisTemplate,
                                 RedisTemplate<String, Object> playerRedisTemplate) {
        return new RedisStore(redisTemplate, playerRedisTemplate);
    }

    @Bean
    public LogicIoExecutors logicIoExecutors(LogicNodeProperties properties) {
        return LogicIoExecutors.fixed(
                properties.getIo().getRedisThreads(),
                properties.getIo().getDbThreads());
    }

    @Bean
    public DBSyncConfig dbSyncConfig(LogicNodeProperties properties) {
        DBSyncConfig config = new DBSyncConfig();
        config.setBatchSize(properties.getDb().getBatchSize());
        config.setBgsaveSeconds(properties.getDb().getBgsaveSeconds());
        config.setFlushConcurrency(properties.getDb().getFlushConcurrency());
        config.setLogPollMs(properties.getDb().getLogPollMs());
        return config;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
        return MybatisPersistenceConfiguration.create(dataSource,
                List.of(PlayerMapper.class),
                List.of("mapper/PlayerMapper.xml"));
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    public PlayerMapper playerMapper(SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(PlayerMapper.class);
    }

    @Bean
    public LogEventMapper logEventMapper(SqlSessionTemplate sqlSessionTemplate) {
        return sqlSessionTemplate.getMapper(LogEventMapper.class);
    }

    @Bean
    public EntityPersistenceRouter entityPersistenceRouter(PlayerMapper playerMapper) {
        return new EntityPersistenceRouter(List.of(playerMapper));
    }

    /**
     * 默认共享引擎：承载所有未单独注册独立引擎的实体（当前为 player）。
     */
    @Bean
    public DBSyncEngine defaultDbSyncEngine(EntityPersistenceRouter entityPersistenceRouter,
                                            DBSyncConfig config,
                                            LogicMetrics logicMetrics) {
        return new DBSyncEngine(config, logicMetrics.engineMetrics("default"),
                entityPersistenceRouter, entityPersistenceRouter);
    }

    /**
     * 引擎路由表：特殊实体按 entityType 挂独立引擎，其余统一走默认共享引擎。
     * 例如新增 guild 实体独立引擎时：new DBSyncEngineRegistry(defaultDbSyncEngine, Map.of("guild", guildDbSyncEngine))。
     */
    @Bean
    public DBSyncEngineRegistry dbSyncEngineRegistry(DBSyncEngine defaultDbSyncEngine) {
        return new DBSyncEngineRegistry(defaultDbSyncEngine, Map.of());
    }

    @Bean
    public LogConsumer logConsumer(StringRedisTemplate redisTemplate,
                                   SqlSessionFactory sqlSessionFactory,
                                   DBSyncConfig config) {
        return new LogConsumer(redisTemplate, sqlSessionFactory, config);
    }

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Bean
    public LogicMetrics logicMetrics(PrometheusMeterRegistry meterRegistry) {
        return new LogicMetrics(meterRegistry);
    }
}
