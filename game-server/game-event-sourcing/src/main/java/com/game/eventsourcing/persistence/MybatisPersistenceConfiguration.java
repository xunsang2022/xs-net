package com.game.eventsourcing.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.game.eventsourcing.log.LogEventMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSessionFactory;

import java.io.IOException;
import java.util.List;
import javax.sql.DataSource;

/** Creates the MyBatis-Plus session factory for the non-Spring-Boot launcher. */
public final class MybatisPersistenceConfiguration {

    private MybatisPersistenceConfiguration() {
    }

    /**
     * 创建基础 SqlSessionFactory，仅注册本模块自带的 LogEventMapper。
     * 外部 Mapper（如 PlayerMapper）由调用方在返回后自行注册。
     */
    public static SqlSessionFactory create(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "logic-postgres",
                new JdbcTransactionFactory(),
                dataSource));
        configuration.setMapUnderscoreToCamelCase(true);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults()
                .setBanner(false);
        globalConfig.getDbConfig().setIdType(IdType.INPUT);
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(LogEventMapper.class);
        parseMapperXml(configuration, "mapper/LogEventMapper.xml");
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 创建 SqlSessionFactory 并注册额外的 Mapper 接口和 XML。
     */
    public static SqlSessionFactory create(DataSource dataSource,
                                           List<Class<?>> additionalMappers,
                                           List<String> additionalMapperXmls) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "logic-postgres",
                new JdbcTransactionFactory(),
                dataSource));
        configuration.setMapUnderscoreToCamelCase(true);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults()
                .setBanner(false);
        globalConfig.getDbConfig().setIdType(IdType.INPUT);
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(LogEventMapper.class);
        parseMapperXml(configuration, "mapper/LogEventMapper.xml");

        for (Class<?> mapper : additionalMappers) {
            configuration.addMapper(mapper);
        }
        for (String xml : additionalMapperXmls) {
            parseMapperXml(configuration, xml);
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapperXml(MybatisConfiguration configuration, String resource) {
        try (var inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load MyBatis mapper XML: " + resource, exception);
        }
    }
}
