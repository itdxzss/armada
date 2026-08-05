package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行 WhatsApp 进群事实 Mapper XML。 */
@SpringJUnitConfig(WhatsappGroupMemberJoinFactMapperH2Test.TestMyBatisConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupMemberJoinFactMapperH2Test {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private WhatsappGroupMemberJoinFactMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE whatsapp_group_member_join_fact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(191) NOT NULL,
                    phone VARCHAR(32),
                    joined_at BIGINT NOT NULL,
                    event_at BIGINT NOT NULL,
                    source_event_id VARCHAR(255) NOT NULL,
                    observer_account_id BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_join UNIQUE (tenant_id, group_jid, participant_jid)
                )
                """);
    }

    @Test
    void selectByGroupJidsIsTenantIsolated() throws SQLException {
        executeSql("""
                INSERT INTO whatsapp_group_member_join_fact
                    (tenant_id, group_jid, participant_jid, phone, joined_at, event_at,
                     source_event_id, observer_account_id, created_at, updated_at)
                VALUES
                    (7, '120363-test@g.us', '15550000001@s.whatsapp.net', '15550000001',
                     100, 100, 'add-100', 10, 1000, 1000),
                    (8, '120363-test@g.us', '15550000002@s.whatsapp.net', '15550000002',
                     300, 300, 'other-tenant', 11, 1200, 1200)
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.phone()).isEqualTo("15550000001");
                    assertThat(row.joinedAt()).isEqualTo(100L);
                });
    }

    @Test
    void upsertSqlKeepsOnlyNewestProtocolFact() throws IOException {
        String xml;
        try (var input = getClass().getResourceAsStream(
                "/mapper/group/WhatsappGroupMemberJoinFactMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("AS incoming")
                .contains("incoming.event_at &gt; whatsapp_group_member_join_fact.event_at")
                .contains("CAST(incoming.source_event_id AS BINARY)")
                .contains("NULLIF(TRIM(whatsapp_group_member_join_fact.phone), '') IS NULL")
                .contains("COALESCE(NULLIF(TRIM(whatsapp_group_member_join_fact.phone), ''),")
                .contains("WHERE tenant_id = #{tenantId}");
        assertThat(xml.indexOf("updated_at = IF"))
                .isLessThan(xml.indexOf("phone = IF"));
        assertThat(xml.indexOf("source_event_id = IF"))
                .isLessThan(xml.indexOf("event_at = IF"));
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:whatsapp_group_join_fact_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/group/WhatsappGroupMemberJoinFactMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupMemberJoinFactMapper mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupMemberJoinFactMapper.class);
        }
    }
}
