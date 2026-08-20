package com.armada.group.mapper;

import com.armada.group.service.GroupExecutableAccountStates;
import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountQuery;
import com.armada.boot.config.MyBatisConfig;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.vo.MarketingTargetCandidateRow;
import com.armada.group.model.vo.AccountGroupMembershipLookup;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
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

/** 账号列表与营销账号树群数量口径的 H2 Mapper XML 回归测试。 */
@SpringJUnitConfig(GroupMembershipCountSemanticsMapperH2Test.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class GroupMembershipCountSemanticsMapperH2Test {

    private static final long TENANT_ID = 7L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private MarketingTaskMapper marketingTaskMapper;

    @Autowired
    private AccountGroupMembershipMapper accountGroupMembershipMapper;

    @Autowired
    private GroupLinkMapper groupLinkMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        execute("DROP ALL OBJECTS");
        execute("CREATE ALIAS SUBSTRING_INDEX FOR '"
                + GroupMembershipCountSemanticsMapperH2Test.class.getName()
                + ".substringIndex'");
        createSchema();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void accountListReadsCurrentBindingWhileMarketingTreeKeepsItsOwnCountSemantics() {
        AccountQuery accountQuery = new AccountQuery();
        accountQuery.setPhone("923300000501");
        accountQuery.setPage(1);
        accountQuery.setPageSize(10);

        assertThat(accountMapper.selectPage(accountQuery))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupsNum()).isEqualTo(1));
        assertThat(marketingTaskMapper.selectAccountTreeAccounts(11L))
                .singleElement()
                .satisfies(row -> assertThat(row.getGroupCount()).isEqualTo(5));
    }

    @Test
    void marketingTargetsReadCurrentBindingAndParticipantFacts() throws SQLException {
        execute("UPDATE wa_account_group_binding "
                + "SET was_in_initial_baseline = 0 WHERE id = 101");
        MarketingTargetCandidateRow current =
                marketingTaskMapper.selectCurrentTargetGroup(501L, 2001L);

        assertThat(current).isNotNull();
        assertThat(current.getGroupJid()).isEqualTo("in-group@g.us");
        assertThat(current.getMembershipStatus()).isEqualTo(1);
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(null, 501L, 100L, null))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");

        execute("""
                UPDATE wa_group_participant
                SET presence_status = 2, last_exit_type = 'REMOVED',
                    presence_observed_at = 200
                WHERE id = 3001
                """);

        assertThat(marketingTaskMapper.selectCurrentTargetGroup(501L, 2001L)).isNull();
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(null, 501L, null, null))
                .singleElement()
                .satisfies(row -> assertThat(row.getMembershipStatus()).isEqualTo(3));
    }

    @Test
    void dynamicRoundExcludesGroupWhileNewGroupFirstSendIsWaiting() throws SQLException {
        execute("""
                INSERT INTO marketing_task_send_attempt
                  (id, tenant_id, target_id, group_jid, round_no, status, detected_at, scheduled_send_at)
                VALUES (9001, 7, 701, 'in-group@g.us', 0, 4, 1000, 1900)
                """);

        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 1_500L)).isEmpty();

        execute("UPDATE marketing_task_send_attempt SET status = 0, attempted_at = 2000 WHERE id = 9001");
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 1_500L)).isEmpty();
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 2_000L)).isEmpty();
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 2_500L))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");
    }

    @Test
    void dynamicRoundKeepsHistoricalGroupWhenNoDelayedFirstSendExists() {
        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 1_500L))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");
    }

    @Test
    void dynamicRoundKeepsImmediateFirstSendWhenDelayIsDisabled() throws SQLException {
        execute("""
                INSERT INTO marketing_task_send_attempt
                  (id, tenant_id, target_id, group_jid, round_no, status,
                   detected_at, scheduled_send_at, attempted_at)
                VALUES (9004, 7, 701, 'in-group@g.us', 0, 0, 2000, 2000, 2000)
                """);

        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, null, 1_500L))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");
    }

    @Test
    void dynamicRoundAdmitsNewGroupAfterPreciseMembershipTimeIsRecorded() throws SQLException {
        execute("UPDATE wa_account_group_binding "
                + "SET membership_active_since_at = NULL WHERE id = 101");

        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, 100L, null))
                .isEmpty();

        execute("UPDATE wa_account_group_binding "
                + "SET membership_active_since_at = 200 WHERE id = 101");

        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, 100L, 1_500L))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");
    }

    @Test
    void dynamicRoundAdmitsCompletedFirstSendWhenPreciseMembershipTimeIsMissing() throws SQLException {
        execute("UPDATE wa_account_group_binding "
                + "SET membership_active_since_at = NULL WHERE id = 101");
        execute("""
                INSERT INTO marketing_task_send_attempt
                  (id, tenant_id, target_id, group_jid, round_no, status,
                   detected_at, attempted_at, scheduled_send_at)
                VALUES (9003, 7, 701, 'in-group@g.us', 0, 2, 1000, 2000, 1900)
                """);

        assertThat(marketingTaskMapper.selectDynamicTargetGroups(701L, 501L, 100L, 2_500L))
                .extracting(MarketingTargetCandidateRow::getGroupJid)
                .containsExactly("in-group@g.us");
    }

    @Test
    void ordinaryAttemptRemainsCoverageAfterAcceptedOutboxLaterFails() throws SQLException {
        execute("""
                INSERT INTO marketing_task_send_attempt
                  (id, tenant_id, target_id, group_jid, round_no, status, outbox_accepted_at)
                VALUES (9002, 7, 701, 'in-group@g.us', 1, 2, 2000)
                """);

        assertThat(marketingTaskMapper.countOrdinarySubmittedOrSuccessfulAttempts(
                701L, "in-group@g.us")).isEqualTo(1);
    }

    @Test
    void sendTimeStatusBatchReadsCurrentParticipantFact() throws SQLException {
        List<AccountGroupMembershipLookup> lookups = List.of(
                new AccountGroupMembershipLookup(501L, "in-group@g.us"));

        assertThat(accountGroupMembershipMapper.selectCurrentStatuses(lookups))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.membershipStatus()).isEqualTo(1);
                    assertThat(row.statusUpdatedAt()).isEqualTo(100L);
                });

        assertThat(accountGroupMembershipMapper.selectCurrentMessageSendPermissions(lookups))
                .singleElement()
                .satisfies(row -> assertThat(row.messageSendAllowed()).isTrue());
        execute("UPDATE wa_group_profile SET announce_only = 1 WHERE group_id = 1001");
        execute("UPDATE wa_group_participant SET role = 1 WHERE id = 3001");
        assertThat(accountGroupMembershipMapper.selectCurrentMessageSendPermissions(lookups))
                .singleElement()
                .satisfies(row -> assertThat(row.messageSendAllowed()).isFalse());

        execute("UPDATE wa_group_participant SET role = 2 WHERE id = 3001");
        assertThat(accountGroupMembershipMapper.selectCurrentMessageSendPermissions(lookups))
                .singleElement()
                .satisfies(row -> assertThat(row.messageSendAllowed()).isTrue());

        execute("UPDATE wa_group_participant SET role = 0 WHERE id = 3001");
        assertThat(accountGroupMembershipMapper.selectCurrentMessageSendPermissions(lookups))
                .singleElement()
                .satisfies(row -> assertThat(row.messageSendAllowed()).isNull());

        execute("""
                UPDATE wa_group_participant
                SET presence_status = 2, last_exit_type = 'LEFT',
                    presence_observed_at = 250
                WHERE id = 3001
                """);

        assertThat(accountGroupMembershipMapper.selectCurrentStatuses(lookups))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.membershipStatus()).isEqualTo(4);
                    assertThat(row.statusUpdatedAt()).isEqualTo(250L);
                });
    }

    @Test
    void historyAndExecutionAccountQueriesReadCanonicalGroupFacts() {
        assertThat(accountGroupMembershipMapper.countHistoricalGroupsByAccountGroup(11L))
                .isEqualTo(1);

        assertThat(accountGroupMembershipMapper.selectHistoricalGroupPageByAccountGroup(
                        11L, 0, 20))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getGroupJid()).isEqualTo("in-group@g.us");
                    assertThat(row.getSubject()).isEqualTo("current subject");
                    assertThat(row.getKnownMembershipCount()).isEqualTo(1);
                    assertThat(row.getInGroupCount()).isEqualTo(1);
                    assertThat(row.getAdminInGroup()).isTrue();
                    assertThat(row.getOperable()).isTrue();
                });
        assertThat(accountGroupMembershipMapper.existsHistoricalGroupByAccountGroup(
                11L, "in-group@g.us")).isTrue();

        GroupExecutionAccount executionAccount =
                accountGroupMembershipMapper.selectHistoricalGroupExecutionAccount(
                        11L, "in-group@g.us");
        assertThat(executionAccount).isNotNull();
        assertThat(executionAccount.accountId()).isEqualTo(501L);
        assertThat(executionAccount.groupAdmin()).isTrue();

        assertThat(accountGroupMembershipMapper.selectPullTaskAdminPromoterCandidatesByTenant(
                        TENANT_ID, "in-group@g.us", 999L))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(501L);
        assertThat(accountGroupMembershipMapper.selectPullTaskAdminDiscoveryCandidatesByTenant(
                        TENANT_ID, "in-group@g.us", 999L, 10))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(501L);
    }

    @Test
    void groupHandleExecutionQueriesReadCanonicalBindingAndParticipant() {
        assertThat(accountGroupMembershipMapper.selectGroupExecutionAccounts(
                        2001L, 1, GroupExecutableAccountStates.executable(), 10))
                .containsExactly(new GroupExecutionAccount(
                        501L, "wa-web", "acc_501", "923300000501", true));
        assertThat(accountGroupMembershipMapper.selectGroupAdminExecutionAccounts(
                        2001L, 1, GroupExecutableAccountStates.executable(), 10))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(501L);
        assertThat(accountGroupMembershipMapper.selectGroupOwnerExecutionAccount(
                        2001L, 1, GroupExecutableAccountStates.executable()))
                .extracting(GroupExecutionAccount::accountId)
                .isEqualTo(501L);
        assertThat(accountGroupMembershipMapper.selectGroupExecutionAccountsByPhones(
                        2001L, List.of("923300000501"), 1, GroupExecutableAccountStates.executable(), 10))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(501L);
    }

    @Test
    void accountGroupSyncRotationAndWatermarkUseCurrentSyncState() throws SQLException {
        assertThat(accountMapper.selectGroupSyncCandidates(1, 1, 2, 2))
                .extracting(row -> row.accountId())
                .containsExactly(501L);

        assertThat(accountMapper.markCurrentGroupSyncRequested(
                TENANT_ID, List.of(501L), 1_000L)).isGreaterThanOrEqualTo(1);
        assertThat(queryLong("""
                SELECT last_sync_requested_at
                FROM account_group_sync_state
                WHERE tenant_id = 7 AND account_id = 501
                """)).isEqualTo(1_000L);

        accountMapper.markCurrentGroupSyncRequested(TENANT_ID, List.of(501L), 900L);
        assertThat(queryLong("""
                SELECT last_sync_requested_at
                FROM account_group_sync_state
                WHERE tenant_id = 7 AND account_id = 501
                """)).isEqualTo(1_000L);
    }

    @Test
    void groupJidHandleLookupPrefersActiveCanonicalReference() throws SQLException {
        execute("""
                INSERT INTO group_link
                  (id, tenant_id, group_id, link_url, group_name, membership_state, deleted_at)
                VALUES
                  (2002, 7, 1001, 'wa://group/archived-alias', 'archived', 2, 900)
                """);

        assertThat(groupLinkMapper.selectIdByGroupJidIncludingDeleted(
                "in-group@g.us")).isEqualTo(2001L);

        execute("UPDATE group_link SET deleted_at = 901 WHERE id = 2001");
        assertThat(groupLinkMapper.selectIdByGroupJidIncludingDeleted(
                "in-group@g.us")).isEqualTo(2001L);
    }

    @Test
    void currentGroupIdentityReadsCanonicalGroupAndInviteReferences() {
        assertThat(groupLinkMapper.selectCurrentIdentity(2001L))
                .satisfies(identity -> {
                    assertThat(identity.groupLinkId()).isEqualTo(2001L);
                    assertThat(identity.groupJid()).isEqualTo("in-group@g.us");
                    assertThat(identity.inviteCode()).isEqualTo("invite-code");
                });
    }

    private void createSchema() throws SQLException {
        execute("""
                CREATE TABLE account (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, ws_phone VARCHAR(32) NOT NULL,
                  account_type TINYINT NOT NULL, device_os TINYINT, number_source TINYINT,
                  channel_name VARCHAR(128), protocol_id VARCHAR(32), protocol_account_id VARCHAR(64),
                  group_baseline_state TINYINT NOT NULL, account_group_id BIGINT, ownership TINYINT NOT NULL,
                  lease_until BIGINT, dispatched_at BIGINT, created_at BIGINT NOT NULL,
                  updated_at BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  account_state TINYINT, login_state TINYINT, risk_status TINYINT, risk_end_time BIGINT,
                  cooldown_until BIGINT, mute_status TINYINT, block_error_code VARCHAR(32),
                  block_reason VARCHAR(255), state_source VARCHAR(64), truth_ip VARCHAR(45),
                  proxy_country VARCHAR(64), proxy_source VARCHAR(64), pull_into_group_count INT,
                  invalidated_at BIGINT, last_state_sync_time BIGINT
                )
                """, """
                CREATE TABLE account_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100),
                  marketing_occupancy_type VARCHAR(32), marketing_occupancy_task_id BIGINT,
                  marketing_locked_at BIGINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_credential (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, cred_format TINYINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE ip_proxy (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  bound_account_id BIGINT, region VARCHAR(64), source VARCHAR(64),
                  status TINYINT, deleted_at BIGINT
                )
                """, """
                CREATE TABLE country (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, name_zh VARCHAR(64), flag VARCHAR(16), deleted_at BIGINT
                )
                """, """
                CREATE TABLE account_group_baseline (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, baseline_group_jids VARCHAR(1024),
                  group_count INT, captured_at BIGINT, last_group_sync_requested_at BIGINT,
                  created_at BIGINT, updated_at BIGINT
                )
                """, """
                CREATE TABLE account_group_sync_state (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, baseline_state TINYINT NOT NULL,
                  baseline_completeness TINYINT NOT NULL, baseline_captured_at BIGINT,
                  baseline_group_count INT, last_sync_requested_at BIGINT,
                  last_reported_at BIGINT, last_snapshot_complete TINYINT NOT NULL,
                  last_complete_at BIGINT, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
                  CONSTRAINT uq_group_sync UNIQUE (tenant_id, account_id)
                )
                """, """
                CREATE TABLE account_group_membership (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, membership_status TINYINT NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE marketing_task_send_attempt (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, target_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, round_no BIGINT NOT NULL, status TINYINT NOT NULL,
                  outbox_accepted_at BIGINT, detected_at BIGINT,
                  attempted_at BIGINT, scheduled_send_at BIGINT
                )
                """, """
                CREATE TABLE wa_account_group_binding (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  participant_id BIGINT NOT NULL, was_in_initial_baseline TINYINT,
                  baseline_subject_snapshot VARCHAR(255),
                  membership_active_since_at BIGINT, last_observed_at BIGINT
                )
                """, """
                CREATE TABLE wa_group (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL, display_name VARCHAR(128), deleted_at BIGINT
                )
                """, """
                CREATE TABLE wa_group_participant (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  presence_status TINYINT NOT NULL, presence_observed_at BIGINT,
                  last_exit_type VARCHAR(16), role TINYINT NOT NULL
                )
                """, """
                CREATE TABLE wa_group_profile (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT NOT NULL,
                  subject VARCHAR(255), member_count INT, checked_member_count INT,
                  wa_created_at BIGINT, announce_only TINYINT, current_invite_id BIGINT,
                  health_status TINYINT, banned TINYINT
                )
                """, """
                CREATE TABLE wa_group_invite (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  invite_code VARCHAR(128) NOT NULL, deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_link (
                  id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, group_id BIGINT,
                  group_invite_id BIGINT,
                  link_url VARCHAR(255), group_name VARCHAR(128), membership_state TINYINT,
                  deleted_at BIGINT
                )
                """, """
                CREATE TABLE group_link_preview (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL, owner_phone VARCHAR(32)
                )
                """);
    }

    private void insertFixtures() throws SQLException {
        execute(
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) VALUES (11, 7, '当前租户组', NULL)",
                """
                INSERT INTO account
                  (id, tenant_id, ws_phone, account_type, ownership, protocol_id, protocol_account_id,
                   group_baseline_state, account_group_id, created_at, deleted_at)
                VALUES
                  (501, 7, '923300000501', 1, 1, 'wa-web', 'acc_501', 3, 11, 100, NULL),
                  (502, 7, '923300000502', 1, 1, 'wa-web', 'acc_502', 3, NULL, 100, NULL),
                  (601, 8, '923300000601', 1, 1, 'wa-web', 'acc_601', 3, 11, 100, NULL)
                """,
                """
                INSERT INTO account_state
                  (tenant_id, account_id, account_state, login_state,
                   risk_status, mute_status)
                VALUES (7, 501, 2, 1, 1, NULL),
                       (7, 502, 2, 1, 1, NULL)
                """,
                """
                INSERT INTO account_group_baseline
                  (tenant_id, account_id, baseline_group_jids, group_count,
                   captured_at, last_group_sync_requested_at, created_at, updated_at)
                VALUES
                  (7, 501, '[]', 0, 100, 9000, 100, 100),
                  (7, 502, '[]', 0, 100, 0, 100, 100)
                """,
                """
                INSERT INTO account_group_sync_state
                  (tenant_id, account_id, baseline_state, baseline_completeness,
                   last_sync_requested_at, last_snapshot_complete, created_at, updated_at)
                VALUES (7, 501, 2, 1, 0, 1, 100, 100),
                       (7, 502, 1, 0, 500, 0, 100, 100)
                """,
                """
                INSERT INTO account_group_membership
                  (id, tenant_id, account_id, group_jid, membership_status, deleted_at)
                VALUES
                  (1, 7, 501, 'in-group@g.us', 1, NULL),
                  (2, 7, 501, 'unconfirmed@g.us', 2, NULL),
                  (3, 7, 501, 'kicked@g.us', 3, NULL),
                  (4, 7, 501, 'left@g.us', 4, NULL),
                  (5, 7, 501, 'not-in-group@g.us', 5, NULL),
                  (6, 7, 501, 'deleted@g.us', 1, 999),
                  (7, 7, 501, '   ', 1, NULL),
                  (8, 8, 601, 'other-tenant@g.us', 1, NULL)
                """,
                """
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id,
                   was_in_initial_baseline, baseline_subject_snapshot,
                   membership_active_since_at, last_observed_at)
                VALUES
                  (101, 7, 501, 1001, 3001, 1, 'baseline subject', 100, 100),
                  (102, 7, 501, 1002, 3002, 0, NULL, 100, 100),
                  (103, 7, 501, 1003, 3003, 0, NULL, 100, 100),
                  (104, 7, 501, 1004, 3004, 0, NULL, 100, 100),
                  (105, 7, 501, 1005, 3005, 0, NULL, 100, 100),
                  (108, 8, 601, 1008, 3008, 0, NULL, 100, 100)
                """,
                """
                INSERT INTO wa_group (id, tenant_id, group_jid, display_name, deleted_at)
                VALUES (1001, 7, 'in-group@g.us', 'current group', NULL)
                """,
                """
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status, presence_observed_at,
                   last_exit_type, role)
                VALUES (3001, 7, 1001, 1, 100, NULL, 2)
                """,
                """
                INSERT INTO wa_group_profile
                  (id, tenant_id, group_id, subject, member_count, checked_member_count,
                   wa_created_at, announce_only, current_invite_id, health_status, banned)
                VALUES (4001, 7, 1001, 'current subject', 30, 31,
                        100000, 0, 5001, 1, 0)
                """,
                """
                INSERT INTO wa_group_invite (id, tenant_id, invite_code)
                VALUES (5001, 7, 'invite-code')
                """,
                """
                INSERT INTO group_link
                  (id, tenant_id, group_id, group_invite_id,
                   link_url, group_name, membership_state, deleted_at)
                VALUES
                  (2001, 7, 1001, 5001, 'https://chat.whatsapp.com/current',
                   'current handle', 2, NULL)
                """,
                """
                INSERT INTO group_link_preview
                  (tenant_id, group_link_id, owner_phone)
                VALUES (7, 2001, '923300000501')
                """);
    }

    /** H2 测试别名：覆盖生产 MySQL 中提取账号号码的 SUBSTRING_INDEX。 */
    public static String substringIndex(String value, String delimiter, int count) {
        if (value == null || delimiter == null || delimiter.isEmpty() || count != 1) {
            return value;
        }
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private void execute(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    /** 本测试只加载两份真实 Mapper XML，并启用生产租户拦截器。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_membership_count_semantics;"
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
            factory.setMapperLocations(
                    new ClassPathResource("mapper/account/AccountMapper.xml"),
                    new ClassPathResource("mapper/group/AccountGroupMembershipMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                    new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountMapper.class);
        }

        @Bean
        MarketingTaskMapper marketingTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(MarketingTaskMapper.class);
        }

        @Bean
        AccountGroupMembershipMapper accountGroupMembershipMapper(SqlSessionTemplate template) {
            return template.getMapper(AccountGroupMembershipMapper.class);
        }

        @Bean
        GroupLinkMapper groupLinkMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupLinkMapper.class);
        }
    }
}
