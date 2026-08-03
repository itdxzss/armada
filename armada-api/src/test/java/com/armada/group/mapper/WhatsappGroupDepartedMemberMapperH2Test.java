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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用 H2 MySQL 模式执行 WhatsApp 退群事实 Mapper XML。 */
@SpringJUnitConfig(WhatsappGroupDepartedMemberMapperH2Test.TestMyBatisConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupDepartedMemberMapperH2Test {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WhatsappGroupDepartedMemberMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE whatsapp_group_departed_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(191) NOT NULL,
                    phone VARCHAR(32),
                    exited_at BIGINT NOT NULL,
                    exit_type VARCHAR(16) NOT NULL,
                    event_at BIGINT NOT NULL,
                    source_event_id VARCHAR(255) NOT NULL,
                    source_type VARCHAR(32) NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_departed UNIQUE (tenant_id, group_jid, participant_jid)
                )
                """);
    }

    @Test
    void selectByGroupJidsIsTenantIsolated() throws SQLException {
        executeSql("""
                INSERT INTO whatsapp_group_departed_member
                    (tenant_id, group_jid, participant_jid, phone, exited_at, exit_type,
                     event_at, source_event_id, source_type, created_at, updated_at)
                VALUES
                    (7, '120363-test@g.us', '15550000001@s.whatsapp.net', '15550000001',
                     100, 'LEFT', 100, 'history-100', 'HISTORY_SYNC', 1000, 1000),
                    (8, '120363-test@g.us', '15550000002@s.whatsapp.net', '15550000002',
                     300, 'REMOVED', 300, 'other-tenant', 'HISTORY_SYNC', 1200, 1200)
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.exitedAt()).isEqualTo(100L);
                    assertThat(row.exitType()).isEqualTo("LEFT");
                });
    }

    @Test
    void upsertSqlKeepsOnlyNewestProtocolFact() throws IOException {
        String xml;
        try (var input = getClass().getResourceAsStream(
                "/mapper/group/WhatsappGroupDepartedMemberMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("AS incoming")
                .contains("incoming.event_at &gt; whatsapp_group_departed_member.event_at")
                .contains("WHEN 'WGP2_NOTIFICATION' THEN 2")
                .contains("CAST(incoming.source_event_id AS BINARY)")
                .contains("CAST(whatsapp_group_departed_member.source_event_id AS BINARY)")
                .contains("COALESCE(NULLIF(TRIM(incoming.phone), ''), whatsapp_group_departed_member.phone)")
                .doesNotContain("VALUES(event_at)")
                .contains("WHERE tenant_id = #{tenantId}");
        assertThat(xml.indexOf("updated_at = IF"))
                .isLessThan(xml.indexOf("source_event_id = IF"));
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
            h2.setURL("jdbc:h2:mem:whatsapp_departed_member_mapper_test"
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
                    new ClassPathResource("mapper/group/WhatsappGroupDepartedMemberMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupDepartedMemberMapper mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupDepartedMemberMapper.class);
        }
    }
}
