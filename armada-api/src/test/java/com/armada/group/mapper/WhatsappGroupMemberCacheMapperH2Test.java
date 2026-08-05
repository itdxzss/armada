package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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

/** 使用 H2 MySQL 模式执行 WhatsApp 群成员缓存查询 XML。 */
@SpringJUnitConfig(WhatsappGroupMemberCacheMapperH2Test.TestMyBatisConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class WhatsappGroupMemberCacheMapperH2Test {

    @org.springframework.beans.factory.annotation.Autowired
    private DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private WhatsappGroupMemberCacheMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS", """
                CREATE TABLE whatsapp_group_member_cache (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    subject VARCHAR(255),
                    announce_only TINYINT,
                    snapshot_at BIGINT NOT NULL,
                    snapshot_version VARCHAR(64) NOT NULL,
                    observer_account_id BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_cache UNIQUE (tenant_id, group_jid)
                )
                """, """
                CREATE TABLE whatsapp_group_member_state (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(191) NOT NULL,
                    phone VARCHAR(32),
                    is_admin TINYINT,
                    is_owner TINYINT,
                    role VARCHAR(32),
                    is_in_group TINYINT NOT NULL,
                    state_source VARCHAR(32) NOT NULL,
                    state_updated_at BIGINT NOT NULL,
                    source_event_id VARCHAR(255) NOT NULL,
                    snapshot_version VARCHAR(64),
                    observer_account_id BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_state UNIQUE (tenant_id, group_jid, participant_jid)
                )
                """);
    }

    @Test
    void selectByGroupJidsReturnsHeaderAndAllKnownMemberStatesForOneTenant() throws SQLException {
        executeSql("""
                INSERT INTO whatsapp_group_member_cache
                    (tenant_id, group_jid, subject, announce_only, snapshot_at, snapshot_version,
                     observer_account_id, created_at, updated_at)
                VALUES
                    (7, '120363-test@g.us', 'tenant-7', 1, 1000, 'v1', 10, 1000, 1000),
                    (8, '120363-test@g.us', 'tenant-8', 0, 1000, 'v1', 11, 1000, 1000)
                """, """
                INSERT INTO whatsapp_group_member_state
                    (tenant_id, group_jid, participant_jid, phone, is_admin, is_owner, role,
                     is_in_group, state_source, state_updated_at, source_event_id,
                     snapshot_version, observer_account_id, created_at, updated_at)
                VALUES
                    (7, '120363-test@g.us', '15550000001@s.whatsapp.net', '15550000001',
                     1, 0, 'admin', 1, 'FULL_SNAPSHOT', 1000, 'snapshot-1', 'v1', 10, 1000, 1000),
                    (7, '120363-test@g.us', '15550000002@s.whatsapp.net', '15550000002',
                     0, 0, '', 0, 'LEAVE_EVENT', 1100, 'leave-1', 'v1', 10, 1100, 1100),
                    (8, '120363-test@g.us', '15550000003@s.whatsapp.net', '15550000003',
                     0, 0, '', 1, 'FULL_SNAPSHOT', 1000, 'other-tenant', 'v1', 11, 1000, 1000)
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.subject()).isEqualTo("tenant-7"))
                .extracting(row -> row.phone())
                .containsExactly("15550000001", "15550000002");
    }

    @Test
    void mysqlUpsertUsesEventOrderingAndSnapshotMissingGuard() throws Exception {
        String xml;
        try (var input = getClass().getResourceAsStream(
                "/mapper/group/WhatsappGroupMemberCacheMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(xml)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("AS incoming")
                .contains("WHEN 'ADD_EVENT' THEN 3")
                .contains("WHEN 'LEAVE_EVENT' THEN 4")
                .contains("WHEN 'UNKNOWN_EXIT_EVENT' THEN 4")
                .contains("WHEN 'SNAPSHOT_ABSENT' THEN 2")
                .contains("NULLIF(TRIM(whatsapp_group_member_state.phone), '')")
                .contains("NULLIF(TRIM(incoming.phone), '')")
                .contains("state_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')")
                .contains("CAST(#{snapshotVersion} AS BINARY)")
                .contains("WHERE cache.tenant_id = #{tenantId}");
        assertThat(xml.indexOf("source_event_id = IF"))
                .isLessThan(xml.indexOf("state_source = IF"));
        assertThat(xml.indexOf("state_source = IF"))
                .isLessThan(xml.indexOf("state_updated_at = GREATEST"));
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
            h2.setURL("jdbc:h2:mem:whatsapp_group_member_cache_mapper_test"
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
                    new ClassPathResource("mapper/group/WhatsappGroupMemberCacheMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        WhatsappGroupMemberCacheMapper mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(WhatsappGroupMemberCacheMapper.class);
        }
    }
}
