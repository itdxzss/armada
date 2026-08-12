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

        GroupMetadataSyncTask withInvite = mapper.selectDueCandidates(
                java.util.List.of(GroupMetadataSyncStatus.PENDING.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 1_000L, 10).get(0);
        assertThat(withInvite.getInviteRequired()).isFalse();
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
    void dueCandidatesRankBatchRefreshBehindRealtimeRefreshEvenWithoutSnapshot() throws SQLException {
        // 批量项多为从未同步过的群。若仍按 last_success_at IS NULL 排最前，一次勾选上千条
        // 就会在 SQL 的 LIMIT 内占满候选，实时事件刷新连候选列表都进不来。
        insertGroupLink("wa://group/batch-group@g.us", 4, "batch-group@g.us", null);
        insertTask(TENANT_ID, pendingTask(GroupMetadataSyncTrigger.BATCH_REFRESH, 1_000L));

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
                .containsExactly(changedGroupLinkId, GROUP_LINK_ID);
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
                         last_success_at, last_error_code, last_error_message, created_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OLD_ERROR', '旧错误', ?, ?)
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
            statement.setObject(10, task.getLastSuccessAt());
            statement.setLong(11, task.getCreatedAt());
            statement.setLong(12, task.getUpdatedAt());
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
                     INSERT INTO group_link (id, tenant_id, link_url, origin, deleted_at)
                     VALUES (?, ?, ?, ?, NULL)
                     """);
             PreparedStatement preview = connection.prepareStatement("""
                     INSERT INTO group_link_preview (
                         tenant_id, group_link_id, group_jid, invite_code
                     ) VALUES (?, ?, ?, ?)
                     """)) {
            link.setLong(1, groupLinkId);
            link.setLong(2, TENANT_ID);
            link.setString(3, linkUrl);
            link.setInt(4, origin);
            link.executeUpdate();
            preview.setLong(1, TENANT_ID);
            preview.setLong(2, groupLinkId);
            preview.setString(3, groupJid);
            preview.setString(4, inviteCode);
            preview.executeUpdate();
        }
    }

    private void resetSchema() throws SQLException {
        execute("DROP ALL OBJECTS");
        execute("""
                CREATE TABLE group_link (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    link_url VARCHAR(255) NOT NULL,
                    origin TINYINT NOT NULL,
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
