package com.game.eventsourcing;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class MybatisPersistenceConfigurationTest {

    @Test
    void loadsXmlMapperStatementsForManualSessionFactory() {
        DataSource dataSource = mock(DataSource.class);

        SqlSessionFactory sessionFactory = MybatisPersistenceConfiguration.create(dataSource);

        assertNotNull(sessionFactory.getConfiguration()
                .getMappedStatement(LogEventMapper.class.getName() + ".insertIgnoreBatch"));
    }
}
