package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 群组列表管理员号码过滤的 H2 MySQL mode 回归测试。 */
@SpringJUnitConfig(GroupLinkControlledAdminMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupLinkControlledAdminMapperInMemoryTest {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupLinkMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listReturnsOnlyActiveControlledAdminAndOwnerPhones() {
        GroupLinkQuery query = pageQuery();

        assertThat(mapper.selectPageByLabel(query))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getAdmin()).isEqualTo("1001, 1002");
                    assertThat(row.getAvailableAdminCount()).isZero();
                });
    }

    @Test
    void keywordMatchesControlledAdminButNotExternalAdmin() {
        GroupLinkQuery controlled = pageQuery();
        controlled.setKeyword("1002");
        assertThat(mapper.countByLabel(controlled)).isEqualTo(1L);
        assertThat(mapper.selectPageByLabel(controlled)).hasSize(1);

        GroupLinkQuery external = pageQuery();
        external.setKeyword("1003");
        assertThat(mapper.countByLabel(external)).isZero();
        assertThat(mapper.selectPageByLabel(external)).isEmpty();
    }

    @Test
    void listPreservesLegacyAliasRowsUnresolvedInvitationAndStableOrder() throws SQLException {
        insertListCompatibilityFixtures();

        GroupLinkQuery query = pageQuery();
        assertThat(mapper.countByLabel(query)).isEqualTo(4L);

        var rows = mapper.selectPageByLabel(query);
        assertThat(rows).extracting(row -> row.getId())
                .containsExactly(204L, 203L, 202L, 201L);
        assertThat(rows).filteredOn(row -> "same-jid@g.us".equals(row.getGroupJid()))
                .extracting(row -> row.getId())
                .containsExactly(203L, 202L);
        assertThat(rows).filteredOn(row -> row.getId().equals(204L))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupJid()).isNull());
    }

    private static GroupLinkQuery pageQuery() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setPage(1);
        query.setPageSize(10);
        return query;
    }

    private void insertListCompatibilityFixtures() throws SQLException {
        execute(
                """
                INSERT INTO group_link
                  (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
                VALUES
                  (202, 7, 'wa://group/same-jid-alias-a@g.us', 5, 2, 200, 200),
                  (203, 7, 'wa://group/same-jid-alias-b@g.us', 5, 2, 200, 200),
                  (204, 7, 'https://chat.whatsapp.com/unresolved-code', 1, 1, 300, 300)
                """,
                """
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, group_jid, wa_subject, member_size)
                VALUES
                  (7, 202, 'same-jid@g.us', '同 JID 别名 A', 10),
                  (7, 203, 'same-jid@g.us', '同 JID 别名 B', 10)
                """);
    }

    private void insertFixtures() throws SQLException {
        execute(
                """
                INSERT INTO group_link
                  (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
                VALUES (201, 7, 'wa://group/controlled-admins@g.us', 5, 2, 100, 100)
                """,
                """
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, group_jid, wa_subject, member_size)
                VALUES (7, 201, 'controlled-admins@g.us', '受控管理员测试群', 5)
                """,
                """
                INSERT INTO account (id, tenant_id, ws_phone, protocol_account_id, deleted_at) VALUES
                  (301, 7, '1001', NULL, NULL),
                  (302, 7, '1002', NULL, NULL),
                  (303, 7, '1004', NULL, NULL),
                  (304, 7, '1005', NULL, 999),
                  (401, 8, '1003', NULL, NULL)
                """,
                """
                INSERT INTO whatsapp_group_member_snapshot
                  (tenant_id, group_link_id, group_jid, participant_jid, phone,
                   role, is_admin, is_owner, snapshot_at, created_at, updated_at)
                VALUES
                  (7, 201, 'controlled-admins@g.us', '1001@s.whatsapp.net', '1001', 'ADMIN', 1, 0, 100, 100, 100),
                  (7, 201, 'controlled-admins@g.us', '1002@s.whatsapp.net', '1002', 'OWNER', 1, 1, 100, 100, 100),
                  (7, 201, 'controlled-admins@g.us', '1003@s.whatsapp.net', '1003', 'ADMIN', 1, 0, 100, 100, 100),
                  (7, 201, 'controlled-admins@g.us', '1004@s.whatsapp.net', '1004', 'MEMBER', 0, 0, 100, 100, 100),
                  (7, 201, 'controlled-admins@g.us', '1005@s.whatsapp.net', '1005', 'ADMIN', 1, 0, 100, 100, 100)
                """);
    }

    private void createSchema() throws SQLException {
        execute(
                """
                CREATE TABLE group_link (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, link_url VARCHAR(255) NOT NULL,
                  group_name VARCHAR(128), label_id BIGINT, folder_id BIGINT, import_batch_id BIGINT,
                  origin TINYINT NOT NULL, membership_state TINYINT NOT NULL,
                  is_historical TINYINT DEFAULT 0, is_post_control TINYINT DEFAULT 0,
                  sync_protocol_mask TINYINT DEFAULT 0, remark VARCHAR(255),
                  deleted_at BIGINT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_link_import_batch (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, source_file_name VARCHAR(255)
                )
                """,
                """
                CREATE TABLE group_link_preview (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128), invite_code VARCHAR(64), wa_subject VARCHAR(255), member_size INT,
                  owner_phone VARCHAR(32), avatar_url VARCHAR(512), last_preview_at BIGINT,
                  creator_country_iso2 VARCHAR(2), creator_continent_code VARCHAR(24), group_created_at BIGINT
                )
                """,
                """
                CREATE TABLE group_link_health (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  health_status TINYINT, is_banned TINYINT, current_count INT,
                  last_check_at BIGINT, last_health_error VARCHAR(64)
                )
                """,
                """
                CREATE TABLE group_folder (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100), deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE country (
                  id BIGINT PRIMARY KEY, iso2 VARCHAR(2), name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE whatsapp_group_member_snapshot (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, participant_jid VARCHAR(128) NOT NULL, phone VARCHAR(32),
                  role VARCHAR(32), is_admin TINYINT DEFAULT 0, is_owner TINYINT DEFAULT 0,
                  snapshot_at BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
                  protocol_account_id VARCHAR(64), deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE account_group_membership (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, membership_status TINYINT NOT NULL,
                  is_admin TINYINT, deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  login_state TINYINT, account_state TINYINT
                )
                """,
                """
                CREATE TABLE group_metadata_sync_task (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  status TINYINT, last_success_at BIGINT, last_error_message VARCHAR(512)
                )
                """);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** 本测试加载真实群组列表 XML，并启用租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_link_controlled_admin_mapper_test;"
                    + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource("mapper/group/GroupLinkMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupLinkMapper groupLinkMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkMapper.class);
        }
    }
}
