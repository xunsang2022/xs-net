package com.game.eventsourcing;

import com.game.eventsourcing.log.LogEventMapper;
import com.game.eventsourcing.persistence.MybatisPersistenceConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class MybatisPersistenceConfigurationTest {

    @Test
    void loadsXmlMapperStatementsForManualSessionFactory() {
        DataSource dataSource = mock(DataSource.class);

        SqlSessionFactory sessionFactory = MybatisPersistenceConfiguration.create(
                dataSource, List.of(), List.of());

        assertNotNull(sessionFactory.getConfiguration()
                .getMappedStatement(LogEventMapper.class.getName() + ".insertIgnoreBatch"));
    }
}
