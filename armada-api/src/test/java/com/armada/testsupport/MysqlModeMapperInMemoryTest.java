package com.armada.testsupport;

import com.armada.group.service.GroupExecutableAccountStates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportLoginResult;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupFolderQuery;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupFolderOptionVO;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingAccountStat;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.shared.tenant.TenantContext;
import com.armada.shared.security.DataScope;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用 H2 MySQL 模式执行本次变更的真实 Mapper XML。
 *
 * <p>该测试只提供无外部依赖的快速 SQL/映射/租户拦截器回归，不能替代真实 MySQL 的锁与方言 DbTest。</p>
 */
@SpringJUnitConfig(MysqlModeMapperInMemoryTest.TestMyBatisPlusConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
public class MysqlModeMapperInMemoryTest {

    private static final long CURRENT_TENANT_ID = 7L;
    private static final DataScope ADMIN_SCOPE = DataScope.all(1L);
    private static final int LARGE_FOLDER_ASSIGNMENT_COUNT = 335;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AccountGroupMapper accountGroupMapper;
    @Autowired
    private AccountImportDetailMapper accountImportDetailMapper;
    @Autowired
    private GroupLinkMapper groupLinkMapper;
    @Autowired
    private GroupFolderMapper groupFolderMapper;
    @Autowired
    private AccountGroupMembershipMapper membershipMapper;
    @Autowired
    private GroupLinkHealthMapper healthMapper;
    @Autowired
    private GroupLinkPreviewMapper previewMapper;
    @Autowired
    private MarketingTaskMapper marketingTaskMapper;
    @Autowired
    private GroupPullMarketingMapper groupPullMarketingMapper;

    @BeforeEach
    void setUp() throws SQLException {
        executeSql("DROP ALL OBJECTS");
        executeSql("CREATE ALIAS SUBSTRING_INDEX FOR '"
                + MysqlModeMapperInMemoryTest.class.getName()
                + ".substringIndex'");
        createSchema();
        TenantContext.set(CURRENT_TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void accountGroupLockQueryExecutesAndKeepsTenantBoundary() throws SQLException {
        executeSql(
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) VALUES (11, 7, 'current', NULL)",
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) VALUES (12, 8, 'other', NULL)");

        List<AccountGroup> groups = transactionTemplate.execute(status -> {
            List<AccountGroup> lockedGroups = accountGroupMapper.selectByIdsForUpdate(List.of(11L, 12L));
            status.setRollbackOnly();
            return lockedGroups;
        });

        assertThat(groups).isNotNull();
        assertThat(groups).extracting(AccountGroup::getId).containsExactly(11L);
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                AccountGroupMapper.class.getName() + ".selectByTenantAndIdsForUpdate")).isTrue();
    }

    @Test
    void groupLinkSoftDeleteAcceptsMoreThanOneHundredIdsAndKeepsTenantBoundary() throws SQLException {
        String currentTenantRows = LongStream.rangeClosed(1, 101)
                .mapToObj(id -> "(%d, 7, 'wa://group/delete-%d', 5, 2, 1, 1)".formatted(id, id))
                .collect(Collectors.joining(","));
        executeSql(
                """
                INSERT INTO group_link
                    (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
                VALUES
                """ + currentTenantRows,
                """
                INSERT INTO group_link
                    (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
                VALUES
                    (1001, 8, 'wa://group/delete-other-tenant', 5, 2, 1, 1)
                """);
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, 101).boxed().toList());
        ids.add(1001L);

        int deleted = groupLinkMapper.softDeleteByIds(
                ids, com.armada.shared.security.DataScope.all(1L), 2L);

        assertThat(deleted).isEqualTo(101);
        assertThat(queryLong("SELECT COUNT(*) FROM group_link WHERE tenant_id = 7 AND deleted_at = 2"))
                .isEqualTo(101);
        assertThat(queryLong("SELECT COUNT(*) FROM group_link WHERE tenant_id = 8 AND deleted_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void groupOwnerExecutionAccountQueryUsesConfirmedOwnerWithoutFallback() throws SQLException {
        executeSql(
                """
                INSERT INTO group_link
                    (id, tenant_id, group_id, link_url, group_name,
                     origin, membership_state, created_at, updated_at)
                VALUES
                    (31, 7, 3101, 'wa://group/owner-current', 'owner-current', 5, 2, 1, 1),
                    (32, 8, 3201, 'wa://group/owner-other', 'owner-other', 5, 2, 1, 1),
                    (33, 7, 3301, 'wa://group/no-preview', 'no-preview', 5, 2, 1, 1)
                """,
                """
                INSERT INTO group_link_preview
                    (id, tenant_id, group_link_id, group_jid, owner_phone, created_at, updated_at)
                VALUES
                    (41, 7, 31, 'owner-current@g.us', '923310000021', 1, 1),
                    (42, 8, 32, 'owner-other@g.us', '923310000021', 1, 1)
                """,
                """
                INSERT INTO account
                    (id, tenant_id, ws_phone, protocol_id, protocol_account_id, created_at)
                VALUES
                    (601, 7, '+923310000021@s.whatsapp.net', 'web', 'owner-601', 1),
                    (602, 7, '923310000022', 'web', 'admin-602', 1),
                    (603, 8, '923310000021', 'web', 'other-owner-603', 1),
                    (604, 7, '923310000024', 'web', 'admin-no-preview-604', 1)
                """,
                """
                INSERT INTO account_state
                    (id, tenant_id, account_id, account_state, login_state)
                VALUES
                    (701, 7, 601, %d, %d),
                    (702, 7, 602, %d, %d),
                    (703, 8, 603, %d, %d),
                    (704, 7, 604, %d, %d)
                """.formatted(
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE,
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE),
                """
                INSERT INTO account_group_membership
                    (id, tenant_id, account_id, group_link_id, group_jid, is_admin,
                     membership_status, status_source, status_updated_at, last_seen_at,
                     created_at, updated_at)
                VALUES
                    (801, 7, 601, 31, 'owner-current@g.us', FALSE,
                     1, 'TEST_FIXTURE', 1, 1, 1, 1),
                    (802, 7, 602, 31, 'owner-current@g.us', TRUE,
                     1, 'TEST_FIXTURE', 2, 2, 1, 1),
                    (803, 8, 603, 32, 'owner-other@g.us', TRUE,
                     1, 'TEST_FIXTURE', 3, 3, 1, 1),
                    (804, 7, 604, 33, 'no-preview@g.us', TRUE,
                     1, 'TEST_FIXTURE', 4, 4, 1, 1)
                """,
                """
                INSERT INTO wa_group_participant
                    (id, tenant_id, group_id, presence_status, role)
                VALUES
                    (901, 7, 3101, 1, 1),
                    (902, 7, 3101, 1, 2),
                    (903, 8, 3201, 1, 3),
                    (904, 7, 3301, 1, 2)
                """,
                """
                INSERT INTO wa_account_group_binding
                    (id, tenant_id, account_id, group_id, participant_id, last_observed_at)
                VALUES
                    (1001, 7, 601, 3101, 901, 1),
                    (1002, 7, 602, 3101, 902, 2),
                    (1003, 8, 603, 3201, 903, 3),
                    (1004, 7, 604, 3301, 904, 4)
                """);

        GroupExecutionAccount owner = membershipMapper.selectGroupOwnerExecutionAccount(
                31L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable());

        assertThat(owner).isNotNull();
        assertThat(owner.accountId()).isEqualTo(601L);
        assertThat(owner.protocolAccountId()).isEqualTo("owner-601");

        assertThat(membershipMapper.selectGroupExecutionAccounts(
                33L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 10))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(604L);
        assertThat(membershipMapper.selectGroupAdminExecutionAccounts(
                33L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable(), 10))
                .extracting(GroupExecutionAccount::accountId)
                .containsExactly(604L);

        try {
            TenantContext.set(8L);
            assertThat(membershipMapper.selectGroupOwnerExecutionAccount(
                    31L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable())).isNull();
        } finally {
            TenantContext.set(CURRENT_TENANT_ID);
        }

        jdbcTemplate.update(
                "UPDATE account_state SET login_state = ? WHERE tenant_id = 7 AND account_id = 601",
                AccountLoginStateCode.OFFLINE);
        assertThat(membershipMapper.selectGroupOwnerExecutionAccount(
                31L, AccountLoginStateCode.ONLINE, GroupExecutableAccountStates.executable())).isNull();
    }

    @Test
    void accountImportDetailMapperReturnsOnlineResultAndCurrentAccountStatus() throws SQLException {
        executeSql(
                "INSERT INTO account_group (id, tenant_id, name, deleted_at) "
                        + "VALUES (11, 7, '当前租户分组', NULL), (12, 8, '其他租户分组', NULL)",
                "INSERT INTO account_import_batch (id, tenant_id, account_group_id) "
                        + "VALUES (101, 7, 11), (201, 8, 12)",
                """
                INSERT INTO account_state
                    (id, tenant_id, account_id, account_state, login_state, block_reason)
                VALUES
                    (301, 7, 501, %d, %d, NULL),
                    (302, 7, 502, %d, %d, 'FORBIDDEN'),
                    (303, 8, 501, %d, %d, NULL)
                """.formatted(
                        AccountStateCode.EXPORTED, AccountLoginStateCode.OFFLINE,
                        AccountStateCode.BANNED, AccountLoginStateCode.OFFLINE,
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE),
                """
                INSERT INTO account_import_detail
                    (id, tenant_id, batch_id, line_no, ws_phone, account_id, parse_result,
                     fail_reason, login_result, online_phase, login_reason, created_at)
                VALUES
                    (401, 7, 101, 1, '8613988000001', 501, 1, NULL, %d, %d, NULL, 100),
                    (402, 7, 101, 2, '8613988000002', 502, 1, NULL, %d, %d, 'LOGIN_TIMEOUT', 100),
                    (403, 8, 201, 1, '819012345678', 501, 1, NULL, %d, %d, NULL, 100)
                """.formatted(
                        AccountImportLoginResult.SUCCESS, AccountImportOnlinePhase.SETTLED,
                        AccountImportLoginResult.FAILED, AccountImportOnlinePhase.SETTLED,
                        AccountImportLoginResult.SUCCESS, AccountImportOnlinePhase.SETTLED));

        AccountImportDetailQuery query = new AccountImportDetailQuery();
        query.setBatchId(101L);
        query.setPageSize(20);

        assertThat(accountImportDetailMapper.countByBatch(query)).isEqualTo(2);
        List<AccountImportDetailVoRow> rows = accountImportDetailMapper.selectPageByBatch(query);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.getWsPhone()).isEqualTo("8613988000001");
            assertThat(row.getOnlinePhase()).isEqualTo(AccountImportOnlinePhase.SETTLED);
            assertThat(row.getLoginResult()).isEqualTo(AccountImportLoginResult.SUCCESS);
            assertThat(row.getAccountState()).isEqualTo(AccountStateCode.EXPORTED);
            assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
            assertThat(row.getAccountStateReason()).isNull();
            assertThat(row.getGroupName()).isEqualTo("当前租户分组");
        });
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.getWsPhone()).isEqualTo("8613988000002");
            assertThat(row.getOnlinePhase()).isEqualTo(AccountImportOnlinePhase.SETTLED);
            assertThat(row.getLoginResult()).isEqualTo(AccountImportLoginResult.FAILED);
            assertThat(row.getLoginReason()).isEqualTo("LOGIN_TIMEOUT");
            assertThat(row.getAccountState()).isEqualTo(AccountStateCode.BANNED);
            assertThat(row.getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
            assertThat(row.getAccountStateReason()).isEqualTo("FORBIDDEN");
        });
    }

    @Test
    void accountImportDispatchMapperSkipsUnownedHistoryAndLocksOneOwnedBatchAtATime() throws SQLException {
        executeSql(
                "INSERT INTO account_import_batch (id, tenant_id, owner_user_id, account_group_id) "
                        + "VALUES (101, 7, 9, 11), (102, 7, 10, 11), (103, 7, NULL, 11), (201, 8, 20, 12)",
                """
                INSERT INTO account_import_detail
                    (id, tenant_id, batch_id, line_no, ws_phone, account_id, parse_result,
                     login_result, online_phase, dispatch_attempts, created_at)
                VALUES
                    (401, 7, 101, 1, '8613988000001', 501, 1, NULL, 1, 0, 100),
                    (402, 7, 101, 2, '8613988000002', 502, 1, NULL, 1, 0, 100),
                    (403, 7, 102, 1, '8613988000003', 503, 1, NULL, 1, 0, 100),
                    (404, 7, 103, 1, '8613988000004', 504, 1, NULL, 1, 0, 100),
                    (405, 8, 201, 1, '819012345678', 601, 1, NULL, 1, 0, 100)
                """);

        assertThat(accountImportDetailMapper.selectQueuedTenantIds(1, 1, 100))
                .containsExactly(7L, 8L);

        List<AccountImportDetail> locked = transactionTemplate.execute(status -> {
            List<AccountImportDetail> rows = accountImportDetailMapper.selectQueuedForUpdate(7L, 1, 1, 500);
            status.setRollbackOnly();
            return rows;
        });

        assertThat(locked).isNotNull();
        assertThat(locked).extracting(AccountImportDetail::getId).containsExactly(401L, 402L);
        assertThat(locked).extracting(AccountImportDetail::getBatchId).containsOnly(101L);
    }

    @Test
    void groupFolderMapperExecutesRealXmlAndKeepsTenantBoundary() throws SQLException {
        executeSql(
                "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                        + "VALUES (101, 7, '印度组', 100, 100)",
                "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                        + "VALUES (102, 8, '其他租户组', 100, 100)",
                "INSERT INTO group_link "
                        + "(id, tenant_id, group_invite_id, link_url, folder_id, origin, "
                        + "membership_state, created_at, updated_at) "
                        + "VALUES (201, 7, 401, 'chat.whatsapp.com/FolderA', 101, 1, 1, 100, 100)",
                "INSERT INTO wa_group_invite "
                        + "(id, tenant_id, invite_code, health_status, banned, updated_at) "
                        + "VALUES (401, 7, 'FolderA', 1, FALSE, 100)");

        GroupFolderQuery query = new GroupFolderQuery();
        query.applyDataScope(ADMIN_SCOPE);
        query.setPage(1);
        query.setPageSize(10);

        assertThat(groupFolderMapper.countPage(query)).isEqualTo(1);
        assertThat(groupFolderMapper.selectPage(query))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.name()).isEqualTo("印度组");
                    assertThat(row.groupCount()).isEqualTo(1L);
                });
        assertThat(groupFolderMapper.selectOptions(ADMIN_SCOPE))
                .extracting(GroupFolderOptionVO::id)
                .containsExactly(101L);
    }

    @Test
    void groupFolderMapperWritesLocksSoftDeletesAndRevives() {
        GroupFolder row = new GroupFolder();
        row.setName("待运营组");
        row.setOwnerUserId(501L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);

        assertThat(groupFolderMapper.insert(row)).isEqualTo(1);
        assertThat(row.getId()).isNotNull();
        assertThat(groupFolderMapper.selectActiveByNameForOwner("待运营组", 501L).getId())
                .isEqualTo(row.getId());

        assertThat(groupFolderMapper.updateName(row.getId(), 501L, "已改名组", 200L))
                .isEqualTo(1);
        assertThat(groupFolderMapper.selectAnyByNameForOwner("已改名组", 501L).getUpdatedAt())
                .isEqualTo(200L);

        List<GroupFolder> locked = transactionTemplate.execute(status -> {
            List<GroupFolder> result = groupFolderMapper.selectActiveByIdsForUpdate(
                    List.of(row.getId()), ADMIN_SCOPE);
            status.setRollbackOnly();
            return result;
        });
        assertThat(locked).isNotNull();
        assertThat(locked).extracting(GroupFolder::getId).containsExactly(row.getId());

        assertThat(groupFolderMapper.softDeleteByIds(
                List.of(row.getId()), ADMIN_SCOPE, 300L)).isEqualTo(1);
        assertThat(groupFolderMapper.selectById(row.getId(), ADMIN_SCOPE)).isNull();
        assertThat(groupFolderMapper.selectDeletedByNameForOwner("已改名组", 501L)
                .getDeletedAt()).isEqualTo(300L);
        GroupFolder revived = new GroupFolder();
        revived.setId(row.getId());
        revived.setName("已改名组");
        revived.setOwnerUserId(501L);
        revived.setUpdatedAt(400L);
        revived.setCreatedBy(501L);
        assertThat(groupFolderMapper.revive(revived)).isEqualTo(1);
        assertThat(groupFolderMapper.selectById(row.getId(), ADMIN_SCOPE).getUpdatedAt())
                .isEqualTo(400L);

        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                GroupFolderMapper.class.getName() + ".selectByTenantAndIdsForUpdate")).isTrue();
    }

    @Test
    void deletingGroupFolderOnlyClearsFolderRelation() throws SQLException {
        executeSql(
                "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                        + "VALUES (101, 7, '印度组', 100, 100)",
                "INSERT INTO group_link "
                        + "(id, tenant_id, link_url, label_id, folder_id, origin, membership_state, "
                        + "created_at, updated_at) "
                        + "VALUES (201, 7, 'chat.whatsapp.com/FolderDelete', 55, 101, 1, 1, 100, 100)");

        assertThat(groupLinkMapper.countActiveByFolderIds(List.of(101L), ADMIN_SCOPE)).isEqualTo(1);
        assertThat(groupLinkMapper.clearFolderByFolderIds(
                List.of(101L), ADMIN_SCOPE, 200L)).isEqualTo(1);
        assertThat(groupFolderMapper.softDeleteByIds(
                List.of(101L), ADMIN_SCOPE, 200L)).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT folder_id, label_id, deleted_at, updated_at FROM group_link WHERE id = 201");
        assertThat(row.get("folder_id")).isNull();
        assertThat(((Number) row.get("label_id")).longValue()).isEqualTo(55L);
        assertThat(row.get("deleted_at")).isNull();
        assertThat(((Number) row.get("updated_at")).longValue()).isEqualTo(200L);
    }

    @Test
    void groupFolderAssignmentLocksAndUpdatesOnlyCurrentTenantGroups() throws SQLException {
        executeSql(
                "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                        + "VALUES (101, 7, '印度组', 100, 100)",
                "INSERT INTO group_link "
                        + "(id, tenant_id, link_url, origin, membership_state, created_at, updated_at) "
                        + "VALUES (201, 7, 'chat.whatsapp.com/Current', 1, 1, 100, 100)",
                "INSERT INTO group_link "
                        + "(id, tenant_id, link_url, origin, membership_state, created_at, updated_at) "
                        + "VALUES (202, 8, 'chat.whatsapp.com/Other', 1, 1, 100, 100)");

        List<GroupLink> locked = transactionTemplate.execute(status -> {
            List<GroupLink> rows = groupLinkMapper.selectActiveByIdsForUpdate(
                    List.of(201L, 202L), ADMIN_SCOPE);
            assertThat(groupLinkMapper.assignFolder(
                    List.of(201L, 202L), 101L, ADMIN_SCOPE, 200L)).isEqualTo(1);
            return rows;
        });

        assertThat(locked).isNotNull();
        assertThat(locked).extracting(GroupLink::getId).containsExactly(201L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM group_link WHERE id = 201", Long.class)).isEqualTo(101L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT folder_id FROM group_link WHERE id = 202", Long.class)).isNull();
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                GroupLinkMapper.class.getName() + ".selectByTenantAndIdsForUpdate")).isTrue();
    }

    @Test
    void groupFolderAssignmentAcceptsMoreThanOneHundredIds() throws SQLException {
        String groupRows = LongStream.rangeClosed(1, LARGE_FOLDER_ASSIGNMENT_COUNT)
                .mapToObj(id -> "(%d, 7, 'wa://group/assign-%d', 5, 2, 1, 1)".formatted(id, id))
                .collect(Collectors.joining(","));
        executeSql(
                "INSERT INTO group_folder (id, tenant_id, name, created_at, updated_at) "
                        + "VALUES (101, 7, '批量分组', 1, 1)",
                """
                INSERT INTO group_link
                    (id, tenant_id, link_url, origin, membership_state, created_at, updated_at)
                VALUES
                """ + groupRows);
        List<Long> ids = LongStream.rangeClosed(1, LARGE_FOLDER_ASSIGNMENT_COUNT).boxed().toList();

        int updated = groupLinkMapper.assignFolder(ids, 101L, ADMIN_SCOPE, 200L);

        assertThat(updated).isEqualTo(ids.size());
        assertThat(queryLong("SELECT COUNT(*) FROM group_link WHERE folder_id = 101"))
                .isEqualTo(ids.size());
    }

    @Test
    void groupPullReleaseCandidateQueryExecutesAndKeepsTenantBoundary() throws SQLException {
        executeSql(
                "INSERT INTO group_pull_marketing_execution "
                        + "(id, tenant_id, task_id, execution_status) VALUES (31, 7, 146, 1)",
                "INSERT INTO group_pull_marketing_execution "
                        + "(id, tenant_id, task_id, execution_status) VALUES (32, 8, 146, 1)");

        List<GroupPullMarketingExecution> executions =
                groupPullMarketingMapper.selectCancelableExecutions(146L);

        assertThat(executions).isNotNull();
        assertThat(executions).extracting(GroupPullMarketingExecution::getId).containsExactly(31L);
        assertThat(InterceptorIgnoreHelper.willIgnoreTenantLine(
                GroupPullMarketingMapper.class.getName()
                        + ".selectCancelableExecutions")).isFalse();
    }

    @Test
    void groupPullNonLockingAllocatorReadsExecuteAndKeepTenantBoundary() throws SQLException {
        executeSql(
                "INSERT INTO group_pull_marketing_task "
                        + "(marketing_task_id, tenant_id, builder_group_id, created_at, updated_at) "
                        + "VALUES (146, 7, 278, 100, 100)",
                "INSERT INTO group_pull_marketing_task "
                        + "(marketing_task_id, tenant_id, builder_group_id, created_at, updated_at) "
                        + "VALUES (147, 8, 279, 100, 100)",
                "INSERT INTO group_pull_marketing_material "
                        + "(id, tenant_id, task_id, line_no, phone, status, created_at, updated_at) "
                        + "VALUES (61, 7, 146, 2, '10002', 1, 100, 100)",
                "INSERT INTO group_pull_marketing_material "
                        + "(id, tenant_id, task_id, line_no, phone, status, created_at, updated_at) "
                        + "VALUES (62, 7, 146, 1, '10001', 1, 100, 100)",
                "INSERT INTO group_pull_marketing_material "
                        + "(id, tenant_id, task_id, line_no, phone, status, created_at, updated_at) "
                        + "VALUES (63, 8, 146, 0, '20001', 1, 100, 100)",
                "INSERT INTO group_pull_marketing_account_stat "
                        + "(id, tenant_id, task_id, account_id, reserved_group_count, "
                        + "joined_group_count, created_at, updated_at) "
                        + "VALUES (71, 7, 146, 88, 1, 2, 100, 100)",
                "INSERT INTO group_pull_marketing_account_stat "
                        + "(id, tenant_id, task_id, account_id, reserved_group_count, "
                        + "joined_group_count, created_at, updated_at) "
                        + "VALUES (72, 8, 146, 88, 9, 9, 100, 100)");

        GroupPullMarketingTask task = groupPullMarketingMapper.selectTaskById(146L);
        List<GroupPullMarketingMaterial> materials =
                groupPullMarketingMapper.selectAvailableMaterials(146L, 5);
        GroupPullMarketingAccountStat stat =
                groupPullMarketingMapper.selectAccountStat(146L, 88L);

        assertThat(task).isNotNull();
        assertThat(task.getTenantId()).isEqualTo(CURRENT_TENANT_ID);
        assertThat(task.getMaterialEntryIntervalSeconds()).isEqualTo(300);
        assertThat(groupPullMarketingMapper.selectTaskById(147L)).isNull();
        assertThat(materials).extracting(GroupPullMarketingMaterial::getId)
                .containsExactly(62L, 61L);
        assertThat(stat).isNotNull();
        assertThat(stat.getTenantId()).isEqualTo(CURRENT_TENANT_ID);
        assertThat(stat.getReservedGroupCount()).isEqualTo(1);
        assertThat(stat.getJoinedGroupCount()).isEqualTo(2);
    }

    @Test
    void groupPullMaterialProgressQueriesExecuteAgainstRealMapperXml() throws SQLException {
        executeSql(
                "INSERT INTO marketing_task "
                        + "(id, tenant_id, business_type, status, task_end_at, deleted_at) "
                        + "VALUES (146, 7, 2, 2, 999999, NULL)",
                "INSERT INTO group_pull_marketing_execution "
                        + "(id, tenant_id, task_id, execution_status, current_stage, "
                        + "stage_retry_count, next_execute_at) VALUES (81, 7, 146, 2, 5, 0, 100)",
                "INSERT INTO group_pull_marketing_material "
                        + "(id, tenant_id, task_id, line_no, phone, status, created_at, updated_at) "
                        + "VALUES (91, 7, 146, 1, '10001', 2, 100, 100)",
                "INSERT INTO group_pull_marketing_execution_material "
                        + "(id, tenant_id, execution_id, material_id, allocation_no, "
                        + "friend_status, entry_status, created_at, updated_at) "
                        + "VALUES (101, 7, 81, 91, 1, 2, 1, 100, 100)");

        GroupPullMarketingExecutionMaterial pending =
                groupPullMarketingMapper.selectNextPendingExecutionMaterial(81L);
        MarketingTask runtime = groupPullMarketingMapper.selectTaskRuntime(146L);
        assertThat(pending).isNotNull();
        assertThat(pending.getId()).isEqualTo(101L);
        assertThat(pending.getMaterialPhone()).isEqualTo("10001");
        assertThat(runtime).isNotNull();
        assertThat(runtime.getStatus()).isEqualTo(2);
        assertThat(runtime.getTaskEndAt()).isEqualTo(999_999L);
        assertThat(groupPullMarketingMapper.updateMaterialStageProgress(
                new GroupPullMarketingMapper.MaterialStageProgress(
                        81L, 2, 5, 0, 1, 240_000L, null, 200L))).isEqualTo(1);
        assertThat(groupPullMarketingMapper.updateMaterialStageProgress(
                new GroupPullMarketingMapper.MaterialStageProgress(
                        81L, 2, 5, 0, 1, 250_000L, null, 201L))).isZero();
        assertThat(groupPullMarketingMapper.rescheduleMaterialExecutionsOnResume(
                146L, 1_000L, 240_000L, 240_000L)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_execute_at FROM group_pull_marketing_execution WHERE id = 81",
                Long.class)).isEqualTo(241_000L);
        assertThat(groupPullMarketingMapper.failPendingExecutionMaterials(
                81L, "任务已停止，未继续拉料", 300L)).isEqualTo(1);
        assertThat(groupPullMarketingMapper.countPendingExecutionMaterials(81L)).isZero();
    }

    @Test
    void groupPullInitialInviteUrlWriteMatchesGroupAndDoesNotOverwrite() throws SQLException {
        executeSql(
                "INSERT INTO group_pull_marketing_execution "
                        + "(id, tenant_id, task_id, group_jid, execution_status, current_stage, "
                        + "stage_retry_count, next_execute_at, created_at, updated_at) "
                        + "VALUES (82, 7, 146, 'invite-group@g.us', 2, 4, 0, 0, 100, 100)");

        assertThat(groupPullMarketingMapper.saveInitialGroupInviteUrl(
                82L,
                "other-group@g.us",
                "https://chat.whatsapp.com/wrong",
                101L)).isZero();
        assertThat(groupPullMarketingMapper.saveInitialGroupInviteUrl(
                82L,
                "invite-group@g.us",
                "https://chat.whatsapp.com/first",
                102L)).isEqualTo(1);
        assertThat(groupPullMarketingMapper.saveInitialGroupInviteUrl(
                82L,
                "invite-group@g.us",
                "https://chat.whatsapp.com/replacement",
                103L)).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT group_invite_url FROM group_pull_marketing_execution WHERE id = 82",
                String.class)).isEqualTo("https://chat.whatsapp.com/first");
    }

    @Test
    void groupPullCandidateQueriesExecuteWithTenantPlugin() throws SQLException {
        executeSql(
                "INSERT INTO account_group "
                        + "(id, tenant_id, name, deleted_at, marketing_occupancy_type, "
                        + "marketing_occupancy_task_id) "
                        + "VALUES (278, 7, 'builder', NULL, NULL, NULL)",
                "INSERT INTO account_group "
                        + "(id, tenant_id, name, deleted_at, marketing_occupancy_type, "
                        + "marketing_occupancy_task_id) "
                        + "VALUES (336, 7, 'marketer', NULL, 2, 157)",
                "INSERT INTO account "
                        + "(id, tenant_id, ws_phone, account_group_id, protocol_id, "
                        + "protocol_account_id, created_at, deleted_at) "
                        + "VALUES (41, 7, '10001', 278, 'android', 'acc_10001', 100, NULL)",
                "INSERT INTO account "
                        + "(id, tenant_id, ws_phone, account_group_id, protocol_id, "
                        + "protocol_account_id, created_at, deleted_at) "
                        + "VALUES (42, 7, '10002', 336, 'android', 'acc_10002', 200, NULL)",
                "INSERT INTO account "
                        + "(id, tenant_id, ws_phone, account_group_id, protocol_id, "
                        + "protocol_account_id, created_at, deleted_at) "
                        + "VALUES (43, 8, '20001', 278, 'android', 'acc_20001', 300, NULL)",
                "INSERT INTO account "
                        + "(id, tenant_id, ws_phone, account_group_id, protocol_id, "
                        + "protocol_account_id, created_at, deleted_at) "
                        + "VALUES (44, 8, '20002', 336, 'android', 'acc_20002', 400, NULL)",
                "INSERT INTO account_state "
                        + "(id, tenant_id, account_id, account_state, login_state, risk_status, mute_status) "
                        + "VALUES (51, 7, 41, 2, 1, 1, NULL)",
                "INSERT INTO account_state "
                        + "(id, tenant_id, account_id, account_state, login_state, risk_status, mute_status) "
                        + "VALUES (52, 7, 42, 2, 1, 1, NULL)",
                "INSERT INTO account_state "
                        + "(id, tenant_id, account_id, account_state, login_state, risk_status, mute_status) "
                        + "VALUES (53, 8, 43, 2, 1, 1, NULL)",
                "INSERT INTO account_state "
                        + "(id, tenant_id, account_id, account_state, login_state, risk_status, mute_status) "
                        + "VALUES (54, 8, 44, 2, 1, 1, NULL)");

        GroupPullAccountRefRow builder =
                groupPullMarketingMapper.selectBuilderCandidate(157L, 278L);
        GroupPullAccountRefRow marketer =
                groupPullMarketingMapper.selectMarketerCandidate(157L, 336L, 10);

        assertThat(builder).isNotNull();
        assertThat(builder.getAccountId()).isEqualTo(41L);
        assertThat(marketer).isNotNull();
        assertThat(marketer.getAccountId()).isEqualTo(42L);
    }

    @Test
    void accountObservedUpsertReturnsExistingIdAndPreservesOwnership() throws SQLException {
        executeSql("""
                INSERT INTO group_link
                    (id, tenant_id, owner_user_id, link_url, group_name, label_id, import_batch_id,
                     origin, membership_state, deleted_at, created_at, updated_at)
                VALUES
                    (21, 7, 1, 'wa://group/120363existing@g.us', '旧群名', 31, 41,
                     1, 3, 1000, 900, 900),
                    (23, 8, 1, 'wa://group/120363existing@g.us', '其他租户群名', 33, 43,
                     2, 1, NULL, 902, 902)
                """);

        GroupLink resolved = transactionTemplate.execute(status -> {
            GroupLink observed = observedGroup(
                    "wa://group/120363existing@g.us", "新群名", 2_000L);

            int affected = groupLinkMapper.upsertAccountObservedGroup(observed, "新群名");
            GroupLink stored = groupLinkMapper.selectAnyByUrlForUpdate(
                    observed.getLinkUrl(), observed.getOwnerUserId());

            assertThat(affected).isPositive();
            return stored;
        });
        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo(21L);

        Map<String, Object> row = queryOne("""
                SELECT id, tenant_id, group_name, label_id, import_batch_id,
                       origin, membership_state, deleted_at, created_at, updated_at
                FROM group_link
                WHERE id = 21
                """);
        assertThat(row)
                .containsEntry("id", 21L)
                .containsEntry("tenant_id", CURRENT_TENANT_ID)
                .containsEntry("group_name", "新群名")
                .containsEntry("label_id", 31L)
                .containsEntry("import_batch_id", 41L)
                .containsEntry("origin", 1)
                .containsEntry("membership_state", 3)
                .containsEntry("created_at", 900L)
                .containsEntry("updated_at", 2_000L);
        assertThat(row.get("deleted_at")).isNull();
        assertThat(queryOne("""
                SELECT group_name, label_id, import_batch_id, origin, membership_state, updated_at
                FROM group_link
                WHERE id = 23
                """))
                .containsEntry("group_name", "其他租户群名")
                .containsEntry("label_id", 33L)
                .containsEntry("import_batch_id", 43L)
                .containsEntry("origin", 2)
                .containsEntry("membership_state", 1)
                .containsEntry("updated_at", 902L);

        executeSql("""
                INSERT INTO group_link
                    (id, tenant_id, owner_user_id, link_url, group_name, label_id, import_batch_id,
                     origin, membership_state, deleted_at, created_at, updated_at)
                VALUES
                    (22, 7, 1, 'wa://group/120363joined@g.us', '保留群名', 32, 42,
                     2, 1, NULL, 901, 901)
                """);
        transactionTemplate.executeWithoutResult(status -> {
            GroupLink observed = observedGroup(
                    "wa://group/120363joined@g.us", "120363joined@g.us", 2_001L);

            groupLinkMapper.upsertAccountObservedGroup(observed, null);
        });
        Map<String, Object> joined = queryOne("""
                SELECT group_name, label_id, import_batch_id, origin, membership_state, updated_at
                FROM group_link
                WHERE id = 22
                """);
        assertThat(joined)
                .containsEntry("group_name", "保留群名")
                .containsEntry("label_id", 32L)
                .containsEntry("import_batch_id", 42L)
                .containsEntry("origin", 2)
                .containsEntry("membership_state", 2)
                .containsEntry("updated_at", 2_001L);
    }

    @Test
    void accountObservedUpsertAccumulatesSyncProtocolMask() throws SQLException {
        GroupLink webObserved = observedGroup(
                "wa://group/120363protocol@g.us", "协议来源群", 2_100L);
        webObserved.setSyncProtocolMask(1);
        GroupLink androidObserved = observedGroup(
                "wa://group/120363protocol@g.us", "协议来源群", 2_101L);
        androidObserved.setSyncProtocolMask(2);

        transactionTemplate.executeWithoutResult(status -> {
            groupLinkMapper.upsertAccountObservedGroup(webObserved, "协议来源群");
            groupLinkMapper.upsertAccountObservedGroup(androidObserved, "协议来源群");
        });

        assertThat(queryLong("""
                SELECT sync_protocol_mask
                FROM group_link
                WHERE link_url = 'wa://group/120363protocol@g.us'
                """))
                .isEqualTo(3L);
    }

    @Test
    void concurrentAccountObservedUpsertCreatesOneRowAndReturnsOneId() throws Exception {
        String linkUrl = "wa://group/120363concurrent@g.us";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Long> first = executor.submit(() -> upsertAfterStart(linkUrl, 3_000L, ready, start));
            Future<Long> second = executor.submit(() -> upsertAfterStart(linkUrl, 3_001L, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Long firstId = first.get(10, TimeUnit.SECONDS);
            Long secondId = second.get(10, TimeUnit.SECONDS);
            assertThat(firstId).isEqualTo(secondId);
            assertThat(queryLong("""
                    SELECT COUNT(*)
                    FROM group_link
                    WHERE tenant_id = 7 AND link_url = 'wa://group/120363concurrent@g.us'
                    """)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void marketingResultSnapshotKeepsSuccessFailureAndTenantSemantics() throws SQLException {
        insertMarketingFixtures();

        int[] affected = transactionTemplate.execute(status -> new int[]{
                marketingTaskMapper.markTargetSuccessFromAttempt(501L, 9_001L, 2_000L),
                marketingTaskMapper.markTargetFailedFromAttempt(
                        502L, 9_002L, "SEND_FAILED", null, 3_000L),
                marketingTaskMapper.markTargetSuccessFromAttempt(504L, 9_004L, 2_500L),
                marketingTaskMapper.markTargetFailedFromAttempt(
                        505L, 9_005L, null, null, 3_500L),
                marketingTaskMapper.markTargetSuccessFromAttempt(503L, 9_003L, 4_000L)
        });
        assertThat(affected).containsExactly(1, 1, 1, 1, 0);

        Map<String, Object> success = queryTarget(501L);
        assertThat(success)
                .containsEntry("group_link_id", 101L)
                .containsEntry("group_jid", "120363success@g.us")
                .containsEntry("group_link_url", "https://chat.whatsapp.com/success")
                .containsEntry("group_name", "attempt-success")
                .containsEntry("status", 5)
                .containsEntry("sent_message_count", 3)
                .containsEntry("failed_message_count", 1)
                .containsEntry("last_attempt_at", 2_000L)
                .containsEntry("last_sent_at", 2_000L)
                .containsEntry("last_reason", "old failure")
                .containsEntry("updated_at", 2_000L);

        Map<String, Object> failed = queryTarget(502L);
        assertThat(failed)
                .containsEntry("group_link_id", 102L)
                .containsEntry("group_jid", "120363failed@g.us")
                .containsEntry("group_link_url", "https://chat.whatsapp.com/failed")
                .containsEntry("group_name", "link-failed")
                .containsEntry("status", 5)
                .containsEntry("sent_message_count", 1)
                .containsEntry("failed_message_count", 3)
                .containsEntry("last_attempt_at", 3_000L)
                .containsEntry("last_sent_at", 800L)
                .containsEntry("last_reason", "SEND_FAILED")
                .containsEntry("updated_at", 3_000L);

        Map<String, Object> cleanSuccess = queryTarget(504L);
        assertThat(cleanSuccess)
                .containsEntry("group_link_id", 104L)
                .containsEntry("group_jid", "target-clean-success@g.us")
                .containsEntry("group_link_url", "https://chat.whatsapp.com/target-clean-success")
                .containsEntry("group_name", "target-clean-success")
                .containsEntry("status", 3)
                .containsEntry("sent_message_count", 1)
                .containsEntry("failed_message_count", 0)
                .containsEntry("last_attempt_at", 2_500L)
                .containsEntry("last_sent_at", 2_500L)
                .containsEntry("updated_at", 2_500L);
        assertThat(cleanSuccess.get("last_reason")).isNull();

        Map<String, Object> cleanFailure = queryTarget(505L);
        assertThat(cleanFailure)
                .containsEntry("group_link_id", 105L)
                .containsEntry("group_jid", "target-clean-failed@g.us")
                .containsEntry("group_link_url", "https://chat.whatsapp.com/target-clean-failed")
                .containsEntry("group_name", "target-clean-failed")
                .containsEntry("status", 4)
                .containsEntry("sent_message_count", 0)
                .containsEntry("failed_message_count", 1)
                .containsEntry("last_attempt_at", 3_500L)
                .containsEntry("last_reason", "发送失败")
                .containsEntry("updated_at", 3_500L);
        assertThat(cleanFailure.get("last_sent_at")).isNull();

        Map<String, Object> otherTenant = queryTarget(503L);
        assertThat(otherTenant)
                .containsEntry("status", 1)
                .containsEntry("sent_message_count", 0)
                .containsEntry("updated_at", 900L);
    }

    @Test
    void marketingResultUpdateWaitsForTargetLockAndKeepsLatestSnapshot() throws Exception {
        insertMarketingFixtures();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);

        try {
            Future<?> holder = executor.submit(() -> {
                TenantContext.set(CURRENT_TENANT_ID);
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        assertThat(marketingTaskMapper.selectTargetForResultUpdate(504L).getId())
                                .isEqualTo(504L);
                        jdbcTemplate.update("""
                        UPDATE marketing_task_target
                        SET group_link_id = 204,
                            group_jid = 'latest@g.us',
                            group_link_url = 'https://chat.whatsapp.com/latest',
                            group_name = 'latest-name',
                            updated_at = 2_400
                        WHERE id = 504
                        """);
                        lockAcquired.countDown();
                        await(releaseLock, "持锁事务未在限定时间内收到释放信号");
                    });
                } finally {
                    TenantContext.clear();
                }
            });

            Future<Integer> contender = executor.submit(() -> {
                TenantContext.set(CURRENT_TENANT_ID);
                try {
                    await(lockAcquired, "持锁事务未在限定时间内获得目标行锁");
                    contenderStarted.countDown();
                    return transactionTemplate.execute(status -> marketingTaskMapper
                            .markTargetSuccessFromAttempt(504L, 9_004L, 2_500L));
                } finally {
                    TenantContext.clear();
                }
            });

            assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(contender.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        assertThat(queryTarget(504L))
                .containsEntry("group_link_id", 204L)
                .containsEntry("group_jid", "latest@g.us")
                .containsEntry("group_link_url", "https://chat.whatsapp.com/latest")
                .containsEntry("group_name", "latest-name")
                .containsEntry("status", 3)
                .containsEntry("sent_message_count", 1)
                .containsEntry("failed_message_count", 0)
                .containsEntry("last_attempt_at", 2_500L)
                .containsEntry("last_sent_at", 2_500L)
                .containsEntry("updated_at", 2_500L);
    }

    private void createSchema() throws SQLException {
        executeSql(
                """
                CREATE TABLE account_group (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    name VARCHAR(100),
                    deleted_at BIGINT,
                    marketing_occupancy_type TINYINT,
                    marketing_occupancy_task_id BIGINT
                )
                """,
                """
                CREATE TABLE account (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    ws_phone VARCHAR(32) NOT NULL,
                    account_group_id BIGINT,
                    protocol_id VARCHAR(32),
                    protocol_account_id VARCHAR(64),
                    created_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE account_state (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    account_state TINYINT,
                    login_state TINYINT,
                    risk_status TINYINT,
                    mute_status TINYINT,
                    block_reason VARCHAR(255)
                )
                """,
                """
                CREATE TABLE account_import_batch (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    account_group_id BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE account_import_detail (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    batch_id BIGINT NOT NULL,
                    line_no INT NOT NULL,
                    ws_phone VARCHAR(32),
                    account_id BIGINT,
                    parse_result TINYINT NOT NULL,
                    fail_reason VARCHAR(255),
                    login_result TINYINT,
                    online_phase TINYINT NOT NULL,
                    online_dispatched_at BIGINT,
                    login_settled_at BIGINT,
                    dispatch_attempts INT DEFAULT 0,
                    login_reason VARCHAR(255),
                    created_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE marketing_account_occupancy (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_pull_marketing_account_stat (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    reserved_group_count INT NOT NULL,
                    joined_group_count INT NOT NULL,
                    created_at BIGINT,
                    updated_at BIGINT
                )
                """,
                """
                CREATE TABLE marketing_task (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    business_type TINYINT NOT NULL,
                    status TINYINT NOT NULL,
                    task_end_at BIGINT,
                    deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE group_pull_marketing_task (
                    marketing_task_id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    builder_group_id BIGINT NOT NULL,
                    success_group_id BIGINT,
                    failure_group_id BIGINT,
                    marketing_account_group_limit INT DEFAULT 10,
                    group_name_prefix VARCHAR(100),
                    friend_retry_limit INT DEFAULT 3,
                    material_per_group INT DEFAULT 3,
                    material_entry_interval_seconds INT NOT NULL DEFAULT 300,
                    speak_permission TINYINT DEFAULT 1,
                    builder_exit_enabled BOOLEAN DEFAULT TRUE,
                    block_reason TINYINT DEFAULT 0,
                    resource_status TINYINT DEFAULT 1,
                    marketing_account_total_count INT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_pull_marketing_material (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    line_no INT NOT NULL,
                    phone VARCHAR(32) NOT NULL,
                    status TINYINT NOT NULL DEFAULT 1,
                    current_execution_id BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_pull_marketing_execution (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    task_id BIGINT NOT NULL,
                    builder_account_id BIGINT,
                    marketing_account_id BIGINT,
                    group_name VARCHAR(255),
                    group_jid VARCHAR(128),
                    group_link_id BIGINT,
                    group_invite_url VARCHAR(255),
                    execution_status TINYINT NOT NULL,
                    current_stage TINYINT,
                    stage_retry_count INT,
                    next_execute_at BIGINT,
                    group_status TINYINT,
                    group_member_count INT,
                    marketer_admin_status TINYINT,
                    builder_exit_status TINYINT,
                    marketing_target_id BIGINT,
                    failure_reason VARCHAR(255),
                    group_created_at BIGINT,
                    finished_at BIGINT,
                    released_at BIGINT,
                    created_at BIGINT,
                    updated_at BIGINT,
                    active_builder_account_id BIGINT
                )
                """,
                """
                CREATE TABLE group_pull_marketing_execution_material (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    execution_id BIGINT NOT NULL,
                    material_id BIGINT NOT NULL,
                    allocation_no INT NOT NULL,
                    friend_status TINYINT NOT NULL,
                    friend_failure_reason VARCHAR(255),
                    entry_status TINYINT NOT NULL,
                    entry_failure_reason VARCHAR(255),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_folder (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    name VARCHAR(100) NOT NULL,
                    system_builtin TINYINT NOT NULL DEFAULT 0,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    created_by BIGINT,
                    deleted_at BIGINT,
                    CONSTRAINT uq_group_folder_name UNIQUE (tenant_id, owner_user_id, name)
                )
                """,
                """
                CREATE TABLE group_link_import_batch (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    source_file_name VARCHAR(255)
                )
                """,
                """
                CREATE TABLE join_task_result (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(64),
                    account VARCHAR(64),
                    is_admin BOOLEAN
                )
                """,
                """
                CREATE TABLE group_link (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    owner_user_id BIGINT,
                    group_id BIGINT,
                    group_invite_id BIGINT,
                    link_url VARCHAR(255) NOT NULL,
                    group_name VARCHAR(128),
                    label_id BIGINT,
                    folder_id BIGINT,
                    import_batch_id BIGINT,
                    origin TINYINT NOT NULL,
                    membership_state TINYINT NOT NULL,
                    is_historical BOOLEAN NOT NULL DEFAULT FALSE,
                    is_post_control BOOLEAN NOT NULL DEFAULT FALSE,
                    sync_protocol_mask TINYINT NOT NULL DEFAULT 0,
                    remark VARCHAR(255),
                    deleted_at BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_link_url UNIQUE (tenant_id, owner_user_id, link_url)
                )
                """,
                """
                CREATE TABLE group_link_preview (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(64),
                    invite_code VARCHAR(128),
                    invite_code_observed_at BIGINT,
                    wa_subject VARCHAR(255),
                    wa_description VARCHAR(1024),
                    member_size INT,
                    owner_phone VARCHAR(32),
                    announce_only BOOLEAN,
                    admin_only_edit_info BOOLEAN,
                    member_add_mode BOOLEAN,
                    join_approval_mode BOOLEAN,
                    ephemeral_duration_seconds INT,
                    group_created_at BIGINT,
                    creator_country_iso2 VARCHAR(2),
                    creator_continent_code VARCHAR(24),
                    creator_phone_region_code VARCHAR(32),
                    creator_phone_region_name VARCHAR(96),
                    avatar_url VARCHAR(512),
                    last_preview_at BIGINT,
                    metadata_observed_at BIGINT,
                    created_at BIGINT,
                    updated_at BIGINT,
                    CONSTRAINT uq_group_link_preview UNIQUE (tenant_id, group_link_id)
                )
                """,
                """
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    created_at BIGINT,
                    updated_at BIGINT
                )
                """,
                """
                CREATE TABLE wa_group_profile (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    current_invite_id BIGINT,
                    subject VARCHAR(255),
                    health_status TINYINT,
                    banned BOOLEAN,
                    last_checked_at BIGINT,
                    created_at BIGINT,
                    updated_at BIGINT
                )
                """,
                """
                CREATE TABLE wa_group_invite (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    invite_code VARCHAR(128) NOT NULL,
                    health_status TINYINT,
                    banned BOOLEAN,
                    deleted_at BIGINT,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE wa_group_participant (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    presence_status TINYINT NOT NULL,
                    role TINYINT NOT NULL
                )
                """,
                """
                CREATE TABLE wa_account_group_binding (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    participant_id BIGINT NOT NULL,
                    last_observed_at BIGINT
                )
                """,
                """
                CREATE TABLE group_link_health (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    health_status TINYINT,
                    is_banned BOOLEAN,
                    current_count INT,
                    last_check_at BIGINT,
                    last_health_error VARCHAR(64),
                    health_failure_count INT,
                    created_at BIGINT,
                    updated_at BIGINT,
                    CONSTRAINT uq_group_link_health UNIQUE (tenant_id, group_link_id)
                )
                """,
                """
                CREATE TABLE country (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    iso2 VARCHAR(2) NOT NULL,
                    name_zh VARCHAR(64) NOT NULL,
                    flag VARCHAR(16),
                    continent_code VARCHAR(24),
                    deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE whatsapp_group_member_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    participant_jid VARCHAR(128) NOT NULL,
                    phone VARCHAR(32),
                    role VARCHAR(32),
                    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
                    is_owner BOOLEAN NOT NULL DEFAULT FALSE,
                    snapshot_at BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE group_metadata_sync_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    status TINYINT NOT NULL,
                    trigger_source TINYINT NOT NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    next_run_at BIGINT,
                    lease_until BIGINT,
                    execution_account_id BIGINT,
                    rerun_requested BOOLEAN NOT NULL DEFAULT FALSE,
                    last_started_at BIGINT,
                    last_success_at BIGINT,
                    last_error_code VARCHAR(64),
                    last_error_message VARCHAR(512),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE account_group_membership (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL,
                    is_admin BOOLEAN,
                    membership_status TINYINT NOT NULL,
                    status_source VARCHAR(64),
                    status_updated_at BIGINT NOT NULL,
                    last_exit_type TINYINT,
                    last_exited_at BIGINT,
                    joined_at BIGINT,
                    last_seen_at BIGINT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    deleted_at BIGINT
                )
                """,
                """
                CREATE TABLE marketing_task_target (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT,
                    group_jid VARCHAR(64),
                    group_link_url VARCHAR(255),
                    group_name VARCHAR(255),
                    status TINYINT NOT NULL,
                    sent_message_count INT NOT NULL,
                    failed_message_count INT NOT NULL,
                    last_attempt_at BIGINT,
                    last_sent_at BIGINT,
                    last_reason VARCHAR(500),
                    updated_at BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE marketing_task_send_attempt (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    target_id BIGINT NOT NULL,
                    group_link_id BIGINT,
                    group_jid VARCHAR(64),
                    group_name VARCHAR(255),
                    outbox_accepted_at BIGINT
                )
                """);
    }

    private void insertMarketingFixtures() throws SQLException {
        executeSql(
                """
                INSERT INTO group_link
                    (id, tenant_id, group_id, link_url, group_name,
                     origin, membership_state, created_at, updated_at)
                VALUES
                    (99, 8, 1099, 'https://chat.whatsapp.com/cross-tenant', 'cross-tenant', 5, 2, 1, 1),
                    (101, 7, 1101, 'https://chat.whatsapp.com/success', 'link-success', 5, 2, 1, 1),
                    (102, 7, 1102, 'https://chat.whatsapp.com/failed', 'link-failed', 5, 2, 1, 1),
                    (103, 8, 1103, 'https://chat.whatsapp.com/other', 'link-other', 5, 2, 1, 1)
                """,
                """
                INSERT INTO wa_group
                    (id, tenant_id, group_jid, created_at, updated_at)
                VALUES
                    (1099, 8, '120363success@g.us', 1, 1),
                    (1101, 7, '120363success@g.us', 1, 1),
                    (1102, 7, '120363failed@g.us', 1, 1),
                    (1103, 8, '120363other@g.us', 1, 1)
                """,
                """
                INSERT INTO wa_group_profile
                    (tenant_id, group_id, subject, created_at, updated_at)
                VALUES
                    (8, 1099, 'cross-tenant-preview', 1, 1),
                    (7, 1101, 'preview-success', 1, 1),
                    (7, 1102, 'preview-failed', 1, 1),
                    (8, 1103, 'preview-other', 1, 1)
                """,
                """
                INSERT INTO marketing_task_target
                    (id, tenant_id, group_link_id, group_jid, group_link_url, group_name,
                     status, sent_message_count, failed_message_count,
                     last_attempt_at, last_sent_at, last_reason, updated_at)
                VALUES
                    (501, 7, NULL, 'target-success@g.us', 'target-success-url', 'target-success',
                     2, 2, 1, 700, 700, 'old failure', 700),
                    (502, 7, NULL, 'target-failed@g.us', 'target-failed-url', 'target-failed',
                     2, 1, 2, 800, 800, 'old reason', 800),
                    (504, 7, 104, 'target-clean-success@g.us', 'https://chat.whatsapp.com/target-clean-success', 'target-clean-success',
                     2, 0, 0, NULL, NULL, 'stale reason', 850),
                    (505, 7, 105, 'target-clean-failed@g.us', 'https://chat.whatsapp.com/target-clean-failed', 'target-clean-failed',
                     2, 0, 0, NULL, NULL, NULL, 860),
                    (503, 8, NULL, 'target-other@g.us', 'target-other-url', 'target-other',
                     1, 0, 0, NULL, NULL, NULL, 900)
                """,
                """
                INSERT INTO marketing_task_send_attempt
                    (id, tenant_id, target_id, group_link_id, group_jid, group_name)
                VALUES
                    (9001, 7, 501, NULL, '120363success@g.us', 'attempt-success'),
                    (9002, 7, 502, 102, '120363failed@g.us', ''),
                    (9004, 7, 504, NULL, NULL, NULL),
                    (9005, 7, 505, NULL, NULL, NULL),
                    (9003, 8, 503, 103, '120363other@g.us', 'attempt-other')
                """);
    }

    private void insertGroupSnapshotFixtures() throws SQLException {
        executeSql(
                """
                INSERT INTO group_link_preview (
                  tenant_id, group_link_id, group_jid, wa_subject, member_size, owner_phone,
                  last_preview_at, created_at, updated_at
                ) VALUES
                  (7, 21, 'current@g.us', '旧群名', 64, '8613800000000', 1, 1, 1),
                  (8, 22, 'other@g.us', '其他租户旧群名', 64, '819012345678', 1, 1, 1)
                """,
                """
                INSERT INTO group_link_health (
                  tenant_id, group_link_id, health_status, is_banned, current_count,
                  last_check_at, health_failure_count, created_at, updated_at
                ) VALUES
                  (7, 21, 1, FALSE, 64, 1, 0, 1, 1),
                  (8, 22, 1, FALSE, 64, 1, 0, 1, 1)
                """,
                """
                INSERT INTO account_group_membership (
                  tenant_id, account_id, group_link_id, group_jid, is_admin,
                  membership_status, status_source, status_updated_at,
                  joined_at, last_seen_at, created_at, updated_at
                ) VALUES
                  (7, 501, 21, 'current@g.us', FALSE, 5, 'LEGACY_MIGRATION', 1, 1, 1, 1, 1),
                  (8, 501, 22, 'other@g.us', FALSE, 5, 'LEGACY_MIGRATION', 1, 1, 1, 1, 1)
                """);
    }

    private GroupLinkPreview previewUpdate() {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(21L);
        row.setGroupJid("current@g.us");
        row.setWaSubject("新群名");
        row.setMemberSize(128);
        row.setLastPreviewAt(2_000L);
        row.setCreatedAt(2_000L);
        row.setUpdatedAt(2_000L);
        return row;
    }

    private void assertOwnerPhone(String expected) throws SQLException {
        assertThat(queryOne("SELECT owner_phone FROM group_link_preview "
                + "WHERE tenant_id = 7 AND group_link_id = 21").get("owner_phone"))
                .isEqualTo(expected);
        assertThat(queryOne("SELECT owner_phone FROM group_link_preview "
                + "WHERE tenant_id = 8 AND group_link_id = 22").get("owner_phone"))
                .isEqualTo("819012345678");
    }

    private GroupLinkHealth healthUpdate() {
        GroupLinkHealth row = new GroupLinkHealth();
        row.setGroupLinkId(21L);
        row.setHealthStatus(1);
        row.setBanned(false);
        row.setCurrentCount(128);
        row.setLastCheckAt(2_000L);
        row.setHealthFailureCount(0);
        row.setUpdatedAt(2_000L);
        return row;
    }

    private AccountGroupMembership membershipUpdate() {
        AccountGroupMembership row = new AccountGroupMembership();
        row.setAccountId(501L);
        row.setGroupLinkId(21L);
        row.setGroupJid("current@g.us");
        row.setAdmin(true);
        row.setMembershipStatus(1);
        row.setStatusSource("GROUP_SNAPSHOT");
        row.setStatusUpdatedAt(2_000L);
        row.setJoinedAt(2_000L);
        row.setLastSeenAt(2_000L);
        row.setUpdatedAt(2_000L);
        return row;
    }

    private Long upsertAfterStart(String linkUrl,
                                  long now,
                                  CountDownLatch ready,
                                  CountDownLatch start) throws Exception {
        TenantContext.set(CURRENT_TENANT_ID);
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("内存库并发 upsert 未在限定时间内开始");
            }
            return transactionTemplate.execute(status -> {
                GroupLink row = observedGroup(linkUrl, "并发观察群", now);
                groupLinkMapper.upsertAccountObservedGroup(row, row.getGroupName());
                GroupLink resolved = groupLinkMapper.selectAnyByUrlForUpdate(
                        linkUrl, row.getOwnerUserId());
                return resolved.getId();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(timeoutMessage);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待事务并发信号时被中断", ex);
        }
    }

    private GroupLink observedGroup(String linkUrl, String groupName, long now) {
        GroupLink row = new GroupLink();
        row.setOwnerUserId(1L);
        row.setLinkUrl(linkUrl);
        row.setGroupName(groupName);
        row.setOrigin(GroupLinkOrigin.ACCOUNT_SYNC.code());
        row.setMembershipState(GroupMembershipState.JOINED.code());
        row.setSyncProtocolMask(1);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private Map<String, Object> queryTarget(long targetId) throws SQLException {
        return queryOne("""
                SELECT group_link_id, group_jid, group_link_url, group_name,
                       status, sent_message_count, failed_message_count,
                       last_attempt_at, last_sent_at, last_reason, updated_at
                FROM marketing_task_target
                WHERE id = %d
                """.formatted(targetId));
    }

    private Map<String, Object> queryOne(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
            }
            assertThat(result.next()).isFalse();
            return row;
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

    /** H2 测试别名：覆盖生产 MySQL 中提取 JID 手机号部分的 SUBSTRING_INDEX。 */
    public static String substringIndex(String value, String delimiter, int count) {
        if (value == null || delimiter == null || delimiter.isEmpty() || count != 1) {
            return value;
        }
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }

    private void executeSql(String... statements) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * 本测试专用的 MyBatis-Plus 配置：使用 H2 数据源，但复用生产租户插件和真实 Mapper XML。
     */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestMyBatisPlusConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:armada_mysql_mode_mapper_test"
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor mybatisPlusInterceptor) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setUseGeneratedKeys(true);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(mybatisPlusInterceptor);
            factoryBean.setMapperLocations(
                    new ClassPathResource("mapper/account/AccountGroupMapper.xml"),
                    new ClassPathResource("mapper/account/AccountImportDetailMapper.xml"),
                    new ClassPathResource("mapper/group/GroupFolderMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                    new ClassPathResource("mapper/group/AccountGroupMembershipMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkPreviewMapper.xml"),
                    new ClassPathResource("mapper/group/GroupLinkHealthMapper.xml"),
                    new ClassPathResource("mapper/marketing/GroupPullMarketingMapper.xml"),
                    new ClassPathResource("mapper/marketing/MarketingTaskMapper.xml"));
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        AccountGroupMapper accountGroupMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(AccountGroupMapper.class);
        }

        @Bean
        AccountImportDetailMapper accountImportDetailMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(AccountImportDetailMapper.class);
        }

        @Bean
        GroupLinkMapper groupLinkMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(GroupLinkMapper.class);
        }

        @Bean
        GroupFolderMapper groupFolderMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(GroupFolderMapper.class);
        }

        @Bean
        AccountGroupMembershipMapper membershipMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(AccountGroupMembershipMapper.class);
        }

        @Bean
        GroupLinkPreviewMapper previewMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(GroupLinkPreviewMapper.class);
        }

        @Bean
        GroupLinkHealthMapper healthMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(GroupLinkHealthMapper.class);
        }

        @Bean
        MarketingTaskMapper marketingTaskMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(MarketingTaskMapper.class);
        }

        @Bean
        GroupPullMarketingMapper groupPullMarketingMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(GroupPullMarketingMapper.class);
        }
    }
}
