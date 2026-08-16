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
                """, """
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL
                )
                """, """
                CREATE TABLE wa_group_profile (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    subject VARCHAR(255),
                    announce_only TINYINT,
                    member_snapshot_at BIGINT,
                    member_snapshot_version VARCHAR(64)
                )
                """, """
                CREATE TABLE wa_group_participant (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    pn_jid VARCHAR(191),
                    lid_jid VARCHAR(191),
                    phone VARCHAR(32),
                    presence_status TINYINT NOT NULL,
                    presence_source VARCHAR(32),
                    presence_observed_at BIGINT,
                    role TINYINT NOT NULL,
                    role_source VARCHAR(32),
                    last_snapshot_version VARCHAR(64),
                    last_joined_at BIGINT,
                    last_exit_source_type VARCHAR(32)
                )
                """);
    }

    @Test
    void selectByGroupJidsReturnsHeaderAndAllKnownMemberStatesForOneTenant() throws SQLException {
        executeSql("""
                INSERT INTO wa_group (id, tenant_id, group_jid)
                VALUES (71, 7, '120363-test@g.us'), (81, 8, '120363-test@g.us')
                """, """
                INSERT INTO wa_group_profile
                    (id, tenant_id, group_id, subject, announce_only,
                     member_snapshot_at, member_snapshot_version)
                VALUES
                    (701, 7, 71, 'tenant-7', 1, 1000, 'v1'),
                    (801, 8, 81, 'tenant-8', 0, 1000, 'v1')
                """, """
                INSERT INTO wa_group_participant
                    (id, tenant_id, group_id, pn_jid, phone, presence_status,
                     presence_source, presence_observed_at, role,
                     role_source, last_snapshot_version, last_joined_at,
                     last_exit_source_type)
                VALUES
                    (711, 7, 71, '15550000001@s.whatsapp.net', '15550000001',
                     1, 'FULL_SNAPSHOT', 1000, 2, 'FULL_SNAPSHOT', 'v1', NULL, NULL),
                    (712, 7, 71, '15550000002@s.whatsapp.net', '15550000002',
                     2, 'LEAVE_EVENT', 1100, 1, NULL, NULL, NULL, 'HISTORY_SYNC'),
                    (713, 7, 71, '15550000004@s.whatsapp.net', '15550000004',
                     1, 'GROUP_SNAPSHOT', 1200, 1, 'GROUP_SNAPSHOT', NULL, NULL, NULL),
                    (714, 7, 71, '15550000005@s.whatsapp.net', '15550000005',
                     1, 'WGP2_PROMOTE', 1300, 2, 'WGP2_PROMOTE', NULL, NULL, NULL),
                    (811, 8, 81, '15550000003@s.whatsapp.net', '15550000003',
                     1, 'FULL_SNAPSHOT', 1000, 1, 'FULL_SNAPSHOT', 'v1', NULL, NULL)
                """);

        assertThat(mapper.selectByGroupJids(7L, java.util.List.of("120363-test@g.us")))
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.subject()).isEqualTo("tenant-7"))
                .extracting(row -> row.phone())
                .containsExactly("15550000001", "15550000002", "15550000005");
    }

    @Test
    void selectStatesByParticipantJidsDoesNotRequireCacheHeader() throws SQLException {
        executeSql("""
                INSERT INTO whatsapp_group_member_state
                    (tenant_id, group_jid, participant_jid, phone, is_admin, is_owner, role,
                     is_in_group, state_source, state_updated_at, source_event_id,
                     snapshot_version, observer_account_id, created_at, updated_at)
                VALUES
                    (7, '120363-test@g.us', '123456789012345@lid', '15550000001',
                     1, 0, 'admin', 1, 'ROLE_EVENT', 2000, 'promote-1', NULL, 10, 2000, 2000),
                    (8, '120363-test@g.us', '123456789012345@lid', '15550000002',
                     0, 0, 'member', 1, 'ROLE_EVENT', 3000, 'other-tenant', NULL, 11, 3000, 3000)
                """);

        assertThat(mapper.selectStatesByParticipantJids(
                7L, "120363-test@g.us", java.util.List.of("123456789012345@lid")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.phone()).isEqualTo("15550000001");
                    assertThat(row.admin()).isTrue();
                    assertThat(row.stateSource()).isEqualTo("ROLE_EVENT");
                    assertThat(row.stateUpdatedAt()).isEqualTo(2_000L);
                    assertThat(row.sourceEventId()).isEqualTo("promote-1");
                });
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
                .contains("WHEN 'LEAVE_EVENT' THEN 5")
                .contains("WHEN 'UNKNOWN_EXIT_EVENT' THEN 5")
                .contains("WHEN 'ROLE_EVENT' THEN 4")
                .contains("WHEN 'MEMBER_QUERY' THEN 2")
                .contains("WHEN 'SNAPSHOT_ABSENT' THEN 2")
                .contains("NULLIF(TRIM(whatsapp_group_member_state.phone), '')")
                .contains("NULLIF(TRIM(incoming.phone), '')")
                .contains("state_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')")
                .contains("CAST(#{snapshotVersion} AS BINARY)")
                .contains("<select id=\"selectStatesByParticipantJids\"")
                .contains("WHERE current_group.tenant_id = #{tenantId}");
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
