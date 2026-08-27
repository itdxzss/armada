package com.armada.group.mapper;

import com.armada.group.service.GroupExecutableAccountStates;
import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.vo.GroupCreatorLeaveAccount;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
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

/** 完整 metadata 中上控成员与账号群关系对齐查询的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(AccountGroupMembershipControlledSnapshotMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class AccountGroupMembershipControlledSnapshotMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountGroupMembershipMapper mapper;

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
    void selectsOnlyCurrentTenantActiveControlledMembersWithFreshRoles() {
        assertThat(mapper.selectControlledMembershipsByGroupLinkId(201L))
                .extracting(
                        AccountGroupMembership::getAccountId,
                        AccountGroupMembership::getGroupLinkId,
                        AccountGroupMembership::getGroupJid,
                        AccountGroupMembership::getAdmin)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                301L, 201L, "120363snapshot@g.us", true),
                        org.assertj.core.groups.Tuple.tuple(
                                302L, 201L, "120363snapshot@g.us", false));
    }

    @Test
    void selectsFreshMetadataAdminBeforeMembershipSnapshotIsPersisted() {
        assertThat(mapper.selectGroupExecutionAccountsByPhones(
                201L, List.of("1001", "1002", "1003", "1004", "1005"),
                1, GroupExecutableAccountStates.executable(), 5))
                .containsExactly(new GroupExecutionAccount(
                        301L, "WEB", "acc_1001", "1001", true));
    }

    @Test
    void selectsCurrentControlledParticipantsForCreatorLeaveFromLocalProjection() {
        assertThat(mapper.selectCreatorLeaveAccounts(201L))
                .extracting(
                        GroupCreatorLeaveAccount::accountId,
                        GroupCreatorLeaveAccount::participantJid,
                        GroupCreatorLeaveAccount::role,
                        GroupCreatorLeaveAccount::loginState,
                        GroupCreatorLeaveAccount::membershipActiveSinceAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                301L, "1001@s.whatsapp.net", 3, 1, 100L),
                        org.assertj.core.groups.Tuple.tuple(
                                302L, "1002@lid", 1, 2, 200L));
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_user_id BIGINT,
                  ws_phone VARCHAR(32) NOT NULL,
                  protocol_id VARCHAR(32), protocol_account_id VARCHAR(64),
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, login_state TINYINT NOT NULL,
                  account_state TINYINT NOT NULL
                )
                """, """
                CREATE TABLE account_group_membership (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, group_link_id BIGINT NOT NULL,
                  membership_status TINYINT NOT NULL, is_admin TINYINT,
                  last_seen_at BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_link (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  owner_user_id BIGINT, group_id BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE wa_account_group_binding (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  participant_id BIGINT NOT NULL, last_observed_at BIGINT,
                  membership_active_since_at BIGINT
                )
                """, """
                CREATE TABLE wa_group_participant (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_id BIGINT NOT NULL, phone VARCHAR(32),
                  pn_jid VARCHAR(128), lid_jid VARCHAR(128),
                  presence_status TINYINT NOT NULL, role TINYINT NOT NULL
                )
                """, """
                CREATE TABLE wa_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL
                )
                """, """
                CREATE TABLE whatsapp_group_member_snapshot (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, group_jid VARCHAR(128) NOT NULL,
                  participant_jid VARCHAR(128) NOT NULL, phone VARCHAR(32),
                  is_admin TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute("""
                INSERT INTO account
                  (id, tenant_id, owner_user_id, ws_phone, protocol_id,
                   protocol_account_id, deleted_at)
                VALUES
                  (301, 7, 1, '1001', 'WEB', 'acc_1001', NULL),
                  (302, 7, 1, '1002', 'ANDROID', 'acc_1002', NULL),
                  (303, 7, 1, '1003', 'WEB', 'acc_1003', 999),
                  (304, 7, 99, '1005', 'WEB', 'acc_1005', NULL),
                  (401, 8, 2, '1004', 'WEB', 'acc_1004', NULL)
                """, """
                INSERT INTO account_state
                  (tenant_id, account_id, login_state, account_state)
                VALUES
                  (7, 301, 1, 2),
                  (7, 302, 2, 2),
                  (7, 303, 1, 2),
                  (7, 304, 1, 2),
                  (8, 401, 1, 2)
                """, """
                INSERT INTO group_link (id, tenant_id, owner_user_id, group_id, deleted_at) VALUES
                  (201, 7, 1, 1001, NULL),
                  (202, 8, 2, 1002, NULL)
                """, """
                INSERT INTO wa_group (id, tenant_id, group_jid) VALUES
                  (1001, 7, '120363snapshot@g.us'),
                  (1002, 8, '120363other@g.us')
                """, """
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, phone, pn_jid, lid_jid, presence_status, role)
                VALUES
                  (5001, 7, 1001, '1001', '1001@s.whatsapp.net', '1001@lid', 1, 3),
                  (5002, 7, 1001, '1002', NULL, '1002@lid', 1, 1),
                  (5003, 7, 1001, '1003', '1003@s.whatsapp.net', NULL, 1, 2),
                  (5004, 7, 1001, '9999', '9999@s.whatsapp.net', NULL, 1, 2),
                  (5006, 7, 1001, '1005', '1005@s.whatsapp.net', NULL, 1, 2),
                  (5005, 8, 1002, '1004', '1004@s.whatsapp.net', NULL, 1, 2)
                """, """
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id,
                   last_observed_at, membership_active_since_at)
                VALUES
                  (6001, 7, 301, 1001, 5001, 300, 100),
                  (6002, 7, 302, 1001, 5002, 300, 200),
                  (6003, 7, 303, 1001, 5003, 300, 300),
                  (6005, 7, 304, 1001, 5006, 300, 500),
                  (6004, 8, 401, 1002, 5005, 300, 400)
                """, """
                INSERT INTO whatsapp_group_member_snapshot
                  (tenant_id, group_link_id, group_jid, participant_jid, phone, is_admin)
                VALUES
                  (7, 201, '120363snapshot@g.us', '1001@s.whatsapp.net', '1001', 1),
                  (7, 201, '120363snapshot@g.us', '1001@lid', '1001', 0),
                  (7, 201, '120363snapshot@g.us', '1002@s.whatsapp.net', '1002', 0),
                  (7, 201, '120363snapshot@g.us', '1003@s.whatsapp.net', '1003', 1),
                  (7, 201, '120363snapshot@g.us', '1004@s.whatsapp.net', '1004', 1),
                  (7, 201, '120363snapshot@g.us', 'external@s.whatsapp.net', '9999', 1),
                  (8, 201, '120363snapshot@g.us', '1004@s.whatsapp.net', '1004', 1)
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

    /** 本测试加载真实账号群关系 Mapper XML，并启用生产租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:controlled_snapshot_membership_mapper_test;"
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
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/AccountGroupMembershipMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        AccountGroupMembershipMapper accountGroupMembershipMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMembershipMapper.class);
        }
    }
}
