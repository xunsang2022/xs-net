package com.game.logic.config;

import com.game.logic.context.LogicIoExecutors;
import com.game.logic.context.LogicMetrics;
import com.game.logic.dbsync.DBSyncConfig;
import com.game.logic.dbsync.DBSyncEngine;
import com.game.logic.dbsync.LogConsumer;
import com.game.logic.dbsync.LogEventMapper;
import com.game.logic.dbsync.MybatisPersistenceConfiguration;
import com.game.logic.dbsync.PlayerMapper;
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
        return MybatisPersistenceConfiguration.create(dataSource);
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
    public DBSyncEngine dbSyncEngine(PlayerMapper playerMapper, DBSyncConfig config) {
        return new DBSyncEngine(playerMapper, config);
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
    public LogicMetrics logicMetrics(PrometheusMeterRegistry meterRegistry,
                                     DBSyncEngine dbSyncEngine) {
        return new LogicMetrics(meterRegistry, dbSyncEngine);
    }
}
