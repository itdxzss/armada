package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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

/** 每租户每群一行的群详情同步任务 Mapper H2 MySQL 模式测试。 */
@SpringJUnitConfig(GroupMetadataSyncTaskMapperDbTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class GroupMetadataSyncTaskMapperDbTest {

    private static final long TENANT_ID = 7L;
    private static final long OTHER_TENANT_ID = 8L;
    private static final long GROUP_LINK_ID = 101L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GroupMetadataSyncTaskMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(TENANT_ID);
        resetSchema();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void duplicateEnqueueKeepsOneRowAndRefreshesPendingTask() {
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.BASELINE_CAPTURED, 1_000L),
                GroupMetadataSyncStatus.RUNNING.code());
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.METADATA_CHANGED, 2_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask task = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(task.getStatus()).isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        assertThat(task.getTriggerSource()).isEqualTo(GroupMetadataSyncTrigger.METADATA_CHANGED.code());
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getNextRunAt()).isEqualTo(2_000L);
        assertThat(task.getRerunRequested()).isFalse();
        assertThat(countTasks(TENANT_ID)).isEqualTo(1);
    }

    @Test
    void inviteOnlyEnqueuePersistsCompletedMetadataScope() {
        GroupMetadataSyncTask inviteOnly =
                pendingTask(GroupMetadataSyncTrigger.BASELINE_CAPTURED, 1_000L);
        inviteOnly.setCompletedScopeMask(1);

        mapper.enqueue(inviteOnly, GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask task = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(task.getCompletedScopeMask()).isEqualTo(1);
    }

    @Test
    void enqueueDuringRunningOnlyRequestsRerunAndPreservesLease() throws SQLException {
        GroupMetadataSyncTask running = storedTask(GroupMetadataSyncStatus.RUNNING, 1_000L);
        running.setAttemptCount(3);
        running.setNextRunAt(1_500L);
        running.setLeaseUntil(9_000L);
        running.setExecutionAccountId(501L);
        insertTask(TENANT_ID, running);

        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.PARTICIPANT_CHANGED, 2_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask task = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(task.getStatus()).isEqualTo(GroupMetadataSyncStatus.RUNNING.code());
        assertThat(task.getAttemptCount()).isEqualTo(3);
        assertThat(task.getNextRunAt()).isEqualTo(1_500L);
        assertThat(task.getLeaseUntil()).isEqualTo(9_000L);
        assertThat(task.getExecutionAccountId()).isEqualTo(501L);
        assertThat(task.getRerunRequested()).isTrue();
        assertThat(task.getTriggerSource())
                .isEqualTo(GroupMetadataSyncTrigger.PARTICIPANT_CHANGED.code());
    }

    @Test
    void terminalAndDeferredTasksResetToPendingWithoutLosingLastSuccess() throws SQLException {
        for (GroupMetadataSyncStatus oldStatus : new GroupMetadataSyncStatus[] {
                GroupMetadataSyncStatus.SUCCEEDED,
                GroupMetadataSyncStatus.DEFERRED,
                GroupMetadataSyncStatus.FAILED
        }) {
            clearTasks();
            GroupMetadataSyncTask oldTask = storedTask(oldStatus, 1_000L);
            oldTask.setAttemptCount(4);
            oldTask.setNextRunAt(7_000L);
            oldTask.setLastSuccessAt(800L);
            insertTask(TENANT_ID, oldTask);

            mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.MANUAL_REFRESH, 2_000L),
                    GroupMetadataSyncStatus.RUNNING.code());

            GroupMetadataSyncTask task = mapper.selectByGroupLinkId(GROUP_LINK_ID);
            assertThat(task.getStatus()).isEqualTo(GroupMetadataSyncStatus.PENDING.code());
            assertThat(task.getAttemptCount()).isZero();
            assertThat(task.getNextRunAt()).isEqualTo(2_000L);
            assertThat(task.getLastSuccessAt()).isEqualTo(800L);
            assertThat(task.getLastErrorCode()).isNull();
            assertThat(task.getLastErrorMessage()).isNull();
        }
    }

    @Test
    void expiredLeaseRecoversOnlyCurrentTenant() throws SQLException {
        GroupMetadataSyncTask currentTenant = storedTask(GroupMetadataSyncStatus.RUNNING, 1_000L);
        currentTenant.setLeaseUntil(1_500L);
        currentTenant.setExecutionAccountId(501L);
        insertTask(TENANT_ID, currentTenant);
        GroupMetadataSyncTask otherTenant = storedTask(GroupMetadataSyncStatus.RUNNING, 1_000L);
        otherTenant.setLeaseUntil(1_500L);
        otherTenant.setExecutionAccountId(601L);
        insertTask(OTHER_TENANT_ID, otherTenant);

        GroupMetadataSyncTask recovery = new GroupMetadataSyncTask();
        recovery.setStatus(GroupMetadataSyncStatus.PENDING.code());
        recovery.setNextRunAt(2_000L);
        recovery.setLastErrorCode("LEASE_EXPIRED");
        recovery.setLastErrorMessage("运行租约已过期");
        recovery.setUpdatedAt(2_000L);
        assertThat(mapper.recoverExpiredLeases(
                recovery, GroupMetadataSyncStatus.RUNNING.code()))
                .isEqualTo(1);

        GroupMetadataSyncTask recovered = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        assertThat(recovered.getStatus()).isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        assertThat(recovered.getExecutionAccountId()).isNull();
        assertThat(recovered.getLeaseUntil()).isNull();
        assertThat(recovered.getLastErrorCode()).isEqualTo("LEASE_EXPIRED");

        TenantContext.set(OTHER_TENANT_ID);
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID).getStatus())
                .isEqualTo(GroupMetadataSyncStatus.RUNNING.code());
    }

    @Test
    void sameGroupLinkIdIsIndependentAcrossTenants() {
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.BACKFILL, 1_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        TenantContext.set(OTHER_TENANT_ID);
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.ACCOUNT_ONLINE, 2_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID).getTriggerSource())
                .isEqualTo(GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code());
        assertThat(countTasks(TENANT_ID)).isEqualTo(1);
        assertThat(countTasks(OTHER_TENANT_ID)).isEqualTo(1);
    }

    @Test
    void dueSelfBuiltGroupWithoutInviteRequiresInviteUntilCodeExists() throws SQLException {
        insertGroupLink("wa://group/120363created@g.us", 4,
                "120363created@g.us", null);
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.BACKFILL, 1_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask missingInvite = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.PENDING.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_000L, 10).get(0);
        assertThat(missingInvite.getGroupJid()).isEqualTo("120363created@g.us");
        assertThat(missingInvite.getInviteRequired()).isTrue();

        execute("UPDATE group_link_preview SET invite_code = 'INVITE-CODE'");
        insertCurrentInvite(GROUP_LINK_ID, "INVITE-CODE");

        GroupMetadataSyncTask withInvite = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.PENDING.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_000L, 10).get(0);
        assertThat(withInvite.getInviteRequired()).isFalse();
    }

    @Test
    void dueCandidateUsesCurrentGroupJidInsteadOfStaleLegacyPreview() throws SQLException {
        insertGroupLink("wa://group/current-group@g.us", 4,
                "current-group@g.us", null);
        execute("UPDATE group_link_preview SET group_jid = 'stale-group@g.us'");
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.BACKFILL, 1_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask due = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.PENDING.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_000L, 10).get(0);

        assertThat(due.getGroupJid()).isEqualTo("current-group@g.us");
    }

    @Test
    void dueCandidateProjectsCurrentMemberSnapshotTime() throws SQLException {
        insertGroupLink("wa://group/current-profile@g.us", 4,
                "current-profile@g.us", null);
        execute("UPDATE wa_group_profile SET member_snapshot_at = 9500 WHERE group_id = 101");
        mapper.enqueue(pendingTask(GroupMetadataSyncTrigger.BASELINE_CAPTURED, 10_000L),
                GroupMetadataSyncStatus.RUNNING.code());

        GroupMetadataSyncTask due = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.PENDING.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 10).get(0);

        assertThat(due.getMemberSnapshotAt()).isEqualTo(9_500L);
    }

    @Test
    void resumeDeferredInviteUsesCurrentSelfPresenceInsteadOfStaleLegacyMembership()
            throws SQLException {
        insertGroupLink("wa://group/resume-current@g.us", 4,
                "resume-current@g.us", null);
        GroupMetadataSyncTask inviteOnly = storedTask(GroupMetadataSyncStatus.DEFERRED, 1_000L);
        inviteOnly.setCompletedScopeMask(1);
        insertTask(TENANT_ID, inviteOnly);
        execute("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status)
                VALUES (801, 7, 101, 1)
                """);
        execute("""
                INSERT INTO wa_account_group_binding
                  (tenant_id, account_id, group_id, participant_id)
                VALUES (7, 501, 101, 801)
                """);
        insertGroupLink(102L, "wa://group/resume-metadata@g.us", 4,
                "resume-metadata@g.us", null);
        GroupMetadataSyncTask metadataPending = storedTask(
                GroupMetadataSyncStatus.DEFERRED, 1_000L);
        metadataPending.setGroupLinkId(102L);
        metadataPending.setCompletedScopeMask(0);
        insertTask(TENANT_ID, metadataPending);
        execute("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status)
                VALUES (802, 7, 102, 1)
                """);
        execute("""
                INSERT INTO wa_account_group_binding
                  (tenant_id, account_id, group_id, participant_id)
                VALUES (7, 501, 102, 802)
                """);
        execute("""
                INSERT INTO account_group_membership
                  (tenant_id, account_id, group_link_id, membership_status, deleted_at)
                VALUES (7, 501, 101, 3, NULL)
                """);

        assertThat(resumeDeferredInviteForAccount(501L, 2_000L)).isEqualTo(1);
        assertThat(mapper.selectByGroupLinkId(102L).getStatus())
                .isEqualTo(GroupMetadataSyncStatus.DEFERRED.code());

        execute("UPDATE group_metadata_sync_task SET status = 5 WHERE group_link_id = 101");
        execute("UPDATE wa_group_participant SET presence_status = 2 WHERE id = 801");
        execute("UPDATE account_group_membership SET membership_status = 1");

        assertThat(resumeDeferredInviteForAccount(501L, 3_000L)).isZero();
    }

    @Test
    void resumeDeferredInviteOnlyLocksTheAccountsOwnInviteTasks() throws SQLException {
        insertGroupLink("wa://group/resume-mine@g.us", 4, "resume-mine@g.us", null);
        GroupMetadataSyncTask inviteOnly = storedTask(GroupMetadataSyncStatus.DEFERRED, 1_000L);
        inviteOnly.setCompletedScopeMask(1);
        insertTask(TENANT_ID, inviteOnly);
        execute("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status)
                VALUES (801, 7, 101, 1)
                """);
        execute("""
                INSERT INTO wa_account_group_binding
                  (tenant_id, account_id, group_id, participant_id)
                VALUES (7, 501, 101, 801)
                """);
        // 同租户另一个群同样处于 DEFERRED，但账号 501 不在其中。
        insertGroupLink(102L, "wa://group/resume-others@g.us", 4, "resume-others@g.us", null);
        GroupMetadataSyncTask foreign = storedTask(GroupMetadataSyncStatus.DEFERRED, 1_000L);
        foreign.setGroupLinkId(102L);
        foreign.setCompletedScopeMask(1);
        insertTask(TENANT_ID, foreign);

        assertThat(mapper.selectDeferredInviteTaskIdsForAccount(
                501L, GroupMetadataSyncStatus.DEFERRED.code(), 1)).hasSize(1);
        assertThat(resumeDeferredInviteForAccount(501L, 2_000L)).isEqualTo(1);

        // 不属于该账号的延期任务既不进入候选主键，也不被写入。
        assertThat(mapper.selectByGroupLinkId(101L).getStatus())
                .isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        assertThat(mapper.selectByGroupLinkId(102L).getStatus())
                .isEqualTo(GroupMetadataSyncStatus.DEFERRED.code());
    }

    /** 复现 service 的两步恢复：先读候选主键，再只按主键写。 */
    private int resumeDeferredInviteForAccount(long accountId, long now) {
        java.util.List<Long> ids = mapper.selectDeferredInviteTaskIdsForAccount(
                accountId, GroupMetadataSyncStatus.DEFERRED.code(), 1);
        if (ids.isEmpty()) {
            return 0;
        }
        assertThat(ids).isSorted();
        return mapper.resumeDeferredByIds(
                ids,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                now);
    }

    @Test
    void legacySucceededTaskWithoutNextRunCanBeClaimedForPeriodicRefresh() throws SQLException {
        insertGroupLink("wa://group/120363created@g.us", 4,
                "120363created@g.us", "INVITE-CODE");
        GroupMetadataSyncTask succeeded = storedTask(GroupMetadataSyncStatus.SUCCEEDED, 1_000L);
        succeeded.setAttemptCount(0);
        succeeded.setNextRunAt(null);
        succeeded.setLastSuccessAt(800L);
        insertTask(TENANT_ID, succeeded);

        GroupMetadataSyncTask due = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.SUCCEEDED.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_000L, 10).get(0);
        GroupMetadataSyncTask claim = new GroupMetadataSyncTask();
        claim.setId(due.getId());
        claim.setTenantId(due.getTenantId());
        claim.setStatus(GroupMetadataSyncStatus.RUNNING.code());
        claim.setAttemptCount(1);
        claim.setExecutionAccountId(501L);
        claim.setLeaseUntil(3_000L);
        claim.setLastStartedAt(1_000L);
        claim.setUpdatedAt(1_000L);

        assertThat(mapper.claim(
                claim,
                java.util.List.of(GroupMetadataSyncStatus.SUCCEEDED.code()),
                GroupMetadataSyncStatus.RUNNING.code(),
                GroupMetadataSyncStatus.SUCCEEDED.code(),
                3,
                1)).isEqualTo(1);
        assertThat(mapper.selectByGroupLinkId(GROUP_LINK_ID).getStatus())
                .isEqualTo(GroupMetadataSyncStatus.RUNNING.code());
    }

    @Test
    void scheduledSucceededTaskIsNotDueBeforePeriodicRefreshTime() throws SQLException {
        insertGroupLink("wa://group/120363created@g.us", 4,
                "120363created@g.us", "INVITE-CODE");
        GroupMetadataSyncTask succeeded = storedTask(GroupMetadataSyncStatus.SUCCEEDED, 2_000L);
        succeeded.setAttemptCount(0);
        succeeded.setLastSuccessAt(1_000L);
        insertTask(TENANT_ID, succeeded);

        assertThat(mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.SUCCEEDED.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_999L, 10)).isEmpty();
        assertThat(mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.SUCCEEDED.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 2_000L, 10)).hasSize(1);
    }

    @Test
    void dueCandidatesPrioritizeGroupsWithoutAnySuccessfulSnapshot() throws SQLException {
        insertGroupLink("wa://group/already-synchronized@g.us", 4,
                "already-synchronized@g.us", "INVITE-CODE");
        GroupMetadataSyncTask refresh = storedTask(GroupMetadataSyncStatus.PENDING, 1_000L);
        refresh.setLastSuccessAt(900L);
        insertTask(TENANT_ID, refresh);

        long newGroupLinkId = 102L;
        insertGroupLink(newGroupLinkId, "wa://group/new-group@g.us", 4,
                "new-group@g.us", null);
        GroupMetadataSyncTask initialSync = pendingTask(GroupMetadataSyncTrigger.BASELINE_CAPTURED, 2_000L);
        initialSync.setGroupLinkId(newGroupLinkId);
        insertTask(TENANT_ID, initialSync);

        long changedGroupLinkId = 103L;
        insertGroupLink(changedGroupLinkId, "wa://group/changed-group@g.us", 4,
                "changed-group@g.us", "CHANGED-INVITE-CODE");
        GroupMetadataSyncTask participantChanged = pendingTask(
                GroupMetadataSyncTrigger.PARTICIPANT_CHANGED, 1_500L);
        participantChanged.setGroupLinkId(changedGroupLinkId);
        participantChanged.setLastSuccessAt(1_400L);
        insertTask(TENANT_ID, participantChanged);

        assertThat(mapper.selectDueCandidates(
                java.util.List.of(
                        GroupMetadataSyncStatus.PENDING.code(),
                        GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 2_000L, 10))
                .extracting(GroupMetadataSyncTask::getGroupLinkId)
                .containsExactly(newGroupLinkId, changedGroupLinkId, GROUP_LINK_ID);
    }

    @Test
    void commandCorrelationUsesCasAndScopeMaskIsIdempotent() throws SQLException {
        insertGroupLink("wa://group/120363snapshot@g.us", 4,
                "120363snapshot@g.us", null);
        GroupMetadataSyncTask running = storedTask(GroupMetadataSyncStatus.RUNNING, 1_000L);
        running.setExecutionAccountId(501L);
        insertTask(TENANT_ID, running);
        GroupMetadataSyncTask stored = mapper.selectByGroupLinkId(GROUP_LINK_ID);
        GroupMetadataSyncTask awaiting = new GroupMetadataSyncTask();
        awaiting.setId(stored.getId());
        awaiting.setTenantId(TENANT_ID);
        awaiting.setCurrentCommandId("cmd-1");
        awaiting.setRequestedScopeMask(3);
        awaiting.setCompletedScopeMask(0);
        awaiting.setCandidateCursor(0);
        awaiting.setResultDeadlineAt(121_000L);
        awaiting.setUpdatedAt(1_000L);

        assertThat(mapper.markAwaitingResult(
                awaiting, GroupMetadataSyncStatus.RUNNING.code())).isEqualTo(1);
        assertThat(mapper.markAwaitingResult(
                awaiting, GroupMetadataSyncStatus.RUNNING.code())).isZero();
        assertThat(mapper.markScopeCompleted("cmd-1", 1, 2_000L)).isEqualTo(1);
        assertThat(mapper.markScopeCompleted("cmd-1", 1, 2_100L)).isEqualTo(1);
        GroupMetadataSyncTask current = mapper.selectByCurrentCommandId(TENANT_ID, "cmd-1");
        assertThat(current.getCompletedScopeMask()).isEqualTo(1);
        assertThat(current.getGroupJid()).isEqualTo("120363snapshot@g.us");
        TenantContext.set(OTHER_TENANT_ID);
        assertThat(mapper.selectByCurrentCommandIdUnscoped("cmd-1").getTenantId())
                .isEqualTo(TENANT_ID);
    }

    private static GroupMetadataSyncTask pendingTask(
            GroupMetadataSyncTrigger trigger,
            long now) {
        GroupMetadataSyncTask row = new GroupMetadataSyncTask();
        row.setGroupLinkId(GROUP_LINK_ID);
        row.setStatus(GroupMetadataSyncStatus.PENDING.code());
        row.setTriggerSource(trigger.code());
        row.setAttemptCount(0);
        row.setNextRunAt(now);
        row.setRerunRequested(false);
        row.setCompletedScopeMask(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static GroupMetadataSyncTask storedTask(
            GroupMetadataSyncStatus status,
            long now) {
        GroupMetadataSyncTask task = pendingTask(GroupMetadataSyncTrigger.BACKFILL, now);
        task.setStatus(status.code());
        task.setAttemptCount(1);
        task.setNextRunAt(now);
        task.setRerunRequested(false);
        return task;
    }

    private void insertTask(long tenantId, GroupMetadataSyncTask task) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO group_metadata_sync_task (
                         tenant_id, group_link_id, status, trigger_source, attempt_count,
                         next_run_at, lease_until, execution_account_id, rerun_requested,
                         completed_scope_mask, last_success_at, last_error_code, last_error_message,
                         created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OLD_ERROR', '旧错误', ?, ?)
                     """)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, task.getGroupLinkId());
            statement.setInt(3, task.getStatus());
            statement.setInt(4, task.getTriggerSource());
            statement.setInt(5, task.getAttemptCount());
            statement.setObject(6, task.getNextRunAt());
            statement.setObject(7, task.getLeaseUntil());
            statement.setObject(8, task.getExecutionAccountId());
            statement.setBoolean(9, Boolean.TRUE.equals(task.getRerunRequested()));
            statement.setInt(10, task.getCompletedScopeMask() == null ? 0 : task.getCompletedScopeMask());
            statement.setObject(11, task.getLastSuccessAt());
            statement.setLong(12, task.getCreatedAt());
            statement.setLong(13, task.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private long countTasks(long tenantId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM group_metadata_sync_task WHERE tenant_id = ?")) {
            statement.setLong(1, tenantId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取任务数量失败", exception);
        }
    }

    private void clearTasks() throws SQLException {
        execute("DELETE FROM group_metadata_sync_task");
    }

    private void insertGroupLink(
            String linkUrl,
            int origin,
            String groupJid,
            String inviteCode) throws SQLException {
        insertGroupLink(GROUP_LINK_ID, linkUrl, origin, groupJid, inviteCode);
    }

    private void insertGroupLink(
            long groupLinkId,
            String linkUrl,
            int origin,
            String groupJid,
            String inviteCode) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement link = connection.prepareStatement("""
                     INSERT INTO group_link (
                         id, tenant_id, group_id, group_invite_id, link_url, origin, deleted_at
                     ) VALUES (?, ?, ?, ?, ?, ?, NULL)
                     """);
             PreparedStatement preview = connection.prepareStatement("""
                     INSERT INTO group_link_preview (
                         tenant_id, group_link_id, group_jid, invite_code
                     ) VALUES (?, ?, ?, ?)
                     """)) {
            link.setLong(1, groupLinkId);
            link.setLong(2, TENANT_ID);
            link.setLong(3, groupLinkId);
            if (inviteCode == null) {
                link.setObject(4, null);
            } else {
                link.setLong(4, groupLinkId);
            }
            link.setString(5, linkUrl);
            link.setInt(6, origin);
            link.executeUpdate();
            preview.setLong(1, TENANT_ID);
            preview.setLong(2, groupLinkId);
            preview.setString(3, groupJid);
            preview.setString(4, inviteCode);
            preview.executeUpdate();
        }
        execute("INSERT INTO wa_group (id, tenant_id, group_jid) VALUES ("
                + groupLinkId + ", " + TENANT_ID + ", '" + groupJid + "')");
        execute("INSERT INTO wa_group_profile (tenant_id, group_id, current_invite_id) VALUES ("
                + TENANT_ID + ", " + groupLinkId + ", "
                + (inviteCode == null ? "NULL" : groupLinkId) + ")");
        if (inviteCode != null) {
            insertCurrentInvite(groupLinkId, inviteCode);
        }
    }

    private void insertCurrentInvite(long groupLinkId, String inviteCode) throws SQLException {
        execute("MERGE INTO wa_group_invite "
                + "(id, tenant_id, group_id, invite_code) KEY(id) VALUES ("
                + groupLinkId + ", " + TENANT_ID + ", " + groupLinkId + ", '"
                + inviteCode + "')");
        execute("UPDATE group_link SET group_invite_id = " + groupLinkId
                + " WHERE tenant_id = " + TENANT_ID + " AND id = " + groupLinkId);
        execute("UPDATE wa_group_profile SET current_invite_id = " + groupLinkId
                + " WHERE tenant_id = " + TENANT_ID + " AND group_id = " + groupLinkId);
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT,
                    group_invite_id BIGINT,
                    link_url VARCHAR(255) NOT NULL,
                    origin TINYINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_jid VARCHAR(128) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE wa_group_profile (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    current_invite_id BIGINT,
                    member_snapshot_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE wa_group_invite (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT,
                    invite_code VARCHAR(128) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE wa_group_participant (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    presence_status TINYINT NOT NULL
                )
                """);
        execute("""
                CREATE TABLE wa_account_group_binding (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    group_id BIGINT NOT NULL,
                    participant_id BIGINT NOT NULL
                )
                """);
        execute("""
                CREATE TABLE account_group_membership (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    membership_status TINYINT NOT NULL,
                    deleted_at BIGINT
                )
                """);
        execute("""
                CREATE TABLE group_link_preview (
                    tenant_id BIGINT NOT NULL,
                    group_link_id BIGINT NOT NULL,
                    group_jid VARCHAR(128),
                    invite_code VARCHAR(64)
                )
                """);
        execute("""
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
                    rerun_requested TINYINT NOT NULL DEFAULT 0,
                    current_command_id VARCHAR(64),
                    requested_scope_mask TINYINT NOT NULL DEFAULT 0,
                    completed_scope_mask TINYINT NOT NULL DEFAULT 0,
                    candidate_cursor INT NOT NULL DEFAULT 0,
                    result_deadline_at BIGINT,
                    last_started_at BIGINT,
                    last_success_at BIGINT,
                    last_error_code VARCHAR(64),
                    last_error_message VARCHAR(512),
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    CONSTRAINT uq_group_metadata_sync_task UNIQUE (tenant_id, group_link_id)
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 本测试所需的最小 MyBatis 与租户拦截器配置。 */
    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:group_metadata_sync_task_mapper_test;"
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
            configuration.setUseGeneratedKeys(true);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            factory.setPlugins(interceptor);
            factory.setMapperLocations(new ClassPathResource(
                    "mapper/group/GroupMetadataSyncTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        GroupMetadataSyncTaskMapper groupMetadataSyncTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(GroupMetadataSyncTaskMapper.class);
        }
    }
}
