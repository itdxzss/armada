package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.dto.PullTaskUnknownReconciliationCriteria;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 群链接执行行 Mapper 的 H2 MySQL 模式测试：链接占用、跨租户调度扫描与调度锁。 */
@SpringJUnitConfig(PullTaskGroupExecutionMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupExecutionMapperInMemoryTest {

    private static final String LINK = "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupExecutionMapper mapper;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insertDraftFillsGeneratedId() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(row.getId()).isNotNull();
        List<PullTaskGroupExecution> saved = mapper.selectByTaskId(100L);
        assertThat(saved).hasSize(1);
        // group_link_id/group_jid 是真实绑定的参数（非强制写死列），必须原样回读。
        assertThat(saved.get(0).getGroupLinkId()).isEqualTo(9000L);
        assertThat(saved.get(0).getGroupJid()).isEqualTo("120363000000000000@g.us");
    }

    @Test
    void twoDraftsMayHoldTheSameLinkBecauseDraftsDoNotOccupy() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        // 草稿 execution_status=0，link_occupancy_key 为 NULL，不参与唯一约束。
        assertThat(mapper.selectByTaskId(100L)).hasSize(1);
        assertThat(mapper.selectByTaskId(200L)).hasSize(1);
    }

    @Test
    void freezingTheSecondTaskOnTheSameLinkIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        assertThat(mapper.freezeDraftRows(100L, 500L)).isEqualTo(1);

        // 第一个任务已占用该链接，第二个任务冻结时唯一键冲突。
        assertThatThrownBy(() -> mapper.freezeDraftRows(200L, 600L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void inviteCodesDifferingOnlyByCaseCoexistWithinOneTask() {
        // H2 默认大小写敏感，这里只验证唯一键维度正确；
        // MySQL 侧的 ai_ci 风险由 PullTaskNormalLinkMigrationSqlTest 的
        // ascii_bin 断言兜住。
        mapper.insertDraft(draft(100L, 1, "chat.whatsapp.com/AAAA", 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/aaaa", 2));

        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void duplicateLinkWithinOneTaskIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        assertThatThrownBy(() -> mapper.insertDraft(draft(100L, 2, LINK, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteDraftRemovesOnlyDraftRowsOfThatTask() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        mapper.insertDraft(draft(100L, 3, "chat.whatsapp.com/CCCC", 3));

        // 只清未冻结的草稿行，已冻结的执行行不受影响。
        assertThat(mapper.deleteDraftByTaskId(100L)).isEqualTo(1);
        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void claimDueScansAcrossTenantsWithoutTenantContext() {
        insertParent(7L, 100L, "EXECUTING");
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.set(8L);
        insertParent(8L, 300L, "EXECUTING");
        mapper.insertDraft(draft(300L, 1, "chat.whatsapp.com/DDDD", 1));
        mapper.freezeDraftRows(300L, 500L);

        // 调度器没有租户上下文；@InterceptorIgnore 让它能看到全部租户的待执行行。
        TenantContext.clear();
        assertThat(mapper.claimDue(claimCriteria(10, 600L, "worker-1", 660L))).isEqualTo(2);
        assertThat(mapper.selectClaimed("worker-1", 600L))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactlyInAnyOrder(100L, 300L);
    }

    @Test
    void claimDueSkipsManuallyPausedAndFutureRows() throws SQLException {
        insertParent(7L, 100L, "EXECUTING");
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        executeRaw("UPDATE pull_task_group_execution SET manual_paused = 1 WHERE seq = 1");
        executeRaw("UPDATE pull_task_group_execution SET next_run_at = 9999 WHERE seq = 2");

        TenantContext.clear();
        // 人工暂停优先于资源自动恢复；未到调度时间的行也不取。
        assertThat(mapper.claimDue(claimCriteria(10, 600L, "worker-1", 660L))).isZero();
    }

    @Test
    void expiredLockCanBeReclaimedByAnotherWorker() {
        insertParent(7L, 100L, "EXECUTING");
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.clear();
        assertThat(mapper.claimDue(claimCriteria(10, 600L, "worker-1", 660L))).isEqualTo(1);
        // 锁未过期时别的实例抢不到。
        assertThat(mapper.claimDue(claimCriteria(10, 610L, "worker-2", 670L))).isZero();
        // 租约到期(660)但尚未被任何实例真正抢占时，selectClaimed 也不应再把它
        // 当作 worker-1 持有——即便 lock_owner 列仍然写着 worker-1。
        assertThat(mapper.selectClaimed("worker-1", 700L)).isEmpty();
        // 锁过期后可被回收，避免实例崩溃导致执行行永久卡死。
        assertThat(mapper.claimDue(claimCriteria(10, 700L, "worker-2", 760L))).isEqualTo(1);
        assertThat(mapper.selectClaimed("worker-1", 700L)).isEmpty();
        assertThat(mapper.selectClaimed("worker-2", 700L))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactly(100L);
    }

    @Test
    void releaseLockClearsOwnershipAndBumpsUpdatedAt() {
        insertParent(7L, 100L, "EXECUTING");
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.clear();
        assertThat(mapper.claimDue(claimCriteria(10, 600L, "worker-1", 660L))).isEqualTo(1);
        assertThat(mapper.selectClaimed("worker-1", 600L)).hasSize(1);

        assertThat(mapper.releaseLock(row.getId(), "worker-1", 650L)).isEqualTo(1);

        // 释放后本实例再也看不到这行；lock_owner/lock_expires_at 清空。
        assertThat(mapper.selectClaimed("worker-1", 650L)).isEmpty();

        TenantContext.set(7L);
        PullTaskGroupExecution released = mapper.selectByTaskId(100L).get(0);
        assertThat(released.getLockOwner()).isNull();
        assertThat(released.getLockExpiresAt()).isNull();
        // releaseLock 是本文件里唯一曾经遗漏 updated_at 的 UPDATE；这里钉住不能再漏。
        assertThat(released.getUpdatedAt()).isEqualTo(650L);
    }

    @Test
    void updateCheckpointRespectsOptimisticLock() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(mapper.updateCheckpoint(row.getId(), 1, 2, 3, 4, 800L, 800L)).isEqualTo(1);
        // 拿旧版本号再提交必须被挡掉。
        assertThat(mapper.updateCheckpoint(row.getId(), 1, 5, 6, 5, 900L, 900L)).isZero();

        PullTaskGroupExecution saved = mapper.selectByTaskId(100L).get(0);
        assertThat(saved.getNextManagerIndex()).isEqualTo(2);
        assertThat(saved.getNextPullerIndex()).isEqualTo(3);
        assertThat(saved.getStage()).isEqualTo(4);
        assertThat(saved.getVersion()).isEqualTo(2);
    }

    @Test
    void managerJoinResultUsesTenantVersionAndStageCasWithoutRowLock() throws SQLException {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);
        executeRaw("UPDATE pull_task_group_execution SET execution_status = 2, stage = 2, "
                + "version = 5, lock_owner = 'worker-old', lock_expires_at = 900 "
                + "WHERE id = " + row.getId());

        PullTaskManagerJoinResultTransition transition = managerJoinSuccess(row.getId(), 5);
        assertThat(mapper.transitionManagerJoinResult(transition)).isZero();

        PullTaskGroupExecution occupied = mapper.selectByTaskId(100L).get(0);
        assertThat(occupied.getLockOwner()).isEqualTo("worker-old");
        assertThat(occupied.getVersion()).isEqualTo(5);

        executeRaw("UPDATE pull_task_group_execution SET lock_owner = NULL, lock_expires_at = NULL "
                + "WHERE id = " + row.getId());
        assertThat(mapper.transitionManagerJoinResult(transition)).isEqualTo(1);

        PullTaskGroupExecution saved = mapper.selectByTaskId(100L).get(0);
        assertThat(saved.getStage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        assertThat(saved.getGroupJid()).isEqualTo("120363verified@g.us");
        assertThat(saved.getVersion()).isEqualTo(6);
        assertThat(saved.getLockOwner()).isNull();
        assertThat(mapper.transitionManagerJoinResult(transition)).isZero();

        TenantContext.set(8L);
        assertThat(mapper.transitionManagerJoinResult(managerJoinSuccess(row.getId(), 6))).isZero();
    }

    @Test
    void protocolResultOnlyAdvancesUnoccupiedExecutionWithCallerSuppliedStates() throws SQLException {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);
        executeRaw("UPDATE pull_task_group_execution SET execution_status = 2, stage = 3, "
                + "version = 5, lock_owner = 'worker-active', lock_expires_at = 900 "
                + "WHERE id = " + row.getId());
        PullTaskExecutionResultTransition transition = new PullTaskExecutionResultTransition(
                row.getId(), 100L, 5,
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                PullTaskExecutionStage.PULLER_INVITE.code(), 4, 0L, 700L);

        assertThat(mapper.transitionProtocolResult(transition)).isZero();
        assertThat(mapper.selectByTaskId(100L).get(0).getLockOwner()).isEqualTo("worker-active");

        executeRaw("UPDATE pull_task_group_execution SET lock_owner = NULL, lock_expires_at = NULL "
                + "WHERE id = " + row.getId());
        assertThat(mapper.transitionProtocolResult(transition)).isEqualTo(1);

        PullTaskGroupExecution advanced = mapper.selectByTaskId(100L).get(0);
        assertThat(advanced.getStage()).isEqualTo(PullTaskExecutionStage.PULLER_INVITE.code());
        assertThat(advanced.getNextPullerIndex()).isEqualTo(4);
        assertThat(advanced.getVersion()).isEqualTo(6);
        assertThat(mapper.transitionProtocolResult(transition)).isZero();
    }

    @Test
    void claimDueRequiresExecutingParentAndSupportedExecutionStage() throws SQLException {
        insertParent(7L, 100L, "WAIT_START");
        insertParent(7L, 200L, "EXECUTING");
        insertParent(7L, 300L, "EXECUTING");
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, "chat.whatsapp.com/BBBB", 1));
        mapper.insertDraft(draft(300L, 1, "chat.whatsapp.com/CCCC", 1));
        mapper.freezeDraftRows(100L, 500L);
        mapper.freezeDraftRows(200L, 500L);
        mapper.freezeDraftRows(300L, 500L);
        executeRaw("UPDATE pull_task_group_execution SET execution_status = 2, stage = 2 "
                + "WHERE task_id = 200");

        TenantContext.clear();
        assertThat(mapper.claimDue(claimCriteria(10, 600L, "worker-1", 660L))).isEqualTo(2);
        assertThat(mapper.selectClaimed("worker-1", 600L))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactly(200L, 300L);
    }

    @Test
    void claimDueUsesCallerSuppliedBusinessConditions() throws SQLException {
        executeRaw("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(400, 7, 'GROUP_MARKETING', 'task', 'OLD_LINK', 'PAUSED', '{}', 100, 100)");
        mapper.insertDraft(draft(400L, 1, "chat.whatsapp.com/DDDD", 1));
        executeRaw("UPDATE pull_task_group_execution "
                + "SET stage = 7, manual_paused = 1 WHERE task_id = 400");

        Map<String, Object> criteria = new HashMap<>();
        criteria.put("lease", Map.of(
                "limit", 10,
                "now", 600L,
                "lockOwner", "worker-custom",
                "lockExpiresAt", 660L));
        criteria.put("eligibleStates", List.of(Map.of(
                "executionStatus", 0,
                "stages", List.of(7))));
        criteria.put("parent", Map.of(
                "taskType", "GROUP_MARKETING",
                "taskMode", "OLD_LINK",
                "taskStatus", "PAUSED"));
        criteria.put("eligibleManualPaused", 1);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("criteria", criteria);
        TenantContext.clear();
        int claimed = sqlSessionTemplate.update(
                "com.armada.task.mapper.PullTaskGroupExecutionMapper.claimDue", parameters);

        assertThat(claimed).isEqualTo(1);
        assertThat(mapper.selectClaimed("worker-custom", 600L))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactly(400L);
    }

    @Test
    void claimedRowStartsOnceAndCompletesCheckpointWithLeaseGuard() {
        insertParent(7L, 100L, "EXECUTING");
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.clear();
        mapper.claimDue(claimCriteria(10, 600L, "worker-1", 900L));
        PullTaskGroupExecution claimed = mapper.selectClaimed("worker-1", 600L).get(0);

        TenantContext.set(7L);
        claimed.setStartedAt(610L);
        claimed.setUpdatedAt(610L);
        assertThat(mapper.startClaimed(claimed)).isEqualTo(1);

        PullTaskGroupExecution started = mapper.selectByTaskId(100L).get(0);
        assertThat(started.getExecutionStatus()).isEqualTo(2);
        assertThat(started.getStartedAt()).isEqualTo(610L);
        assertThat(started.getVersion()).isEqualTo(2);
        assertThat(started.getLockOwner()).isEqualTo("worker-1");
        assertThat(mapper.selectByTaskId(100L))
                .extracting(PullTaskGroupExecution::getExecutionStatus)
                .containsExactly(PullTaskExecutionStatus.EXECUTING.code());

        started.setStage(2);
        started.setReasonCode(null);
        started.setReasonMessage(null);
        started.setNextRunAt(0L);
        started.setFinishedAt(null);
        started.setLastBusinessExecutedAt(620L);
        started.setUpdatedAt(620L);
        assertThat(mapper.transitionClaimed(started, 1)).isEqualTo(1);

        PullTaskGroupExecution advanced = mapper.selectByTaskId(100L).get(0);
        assertThat(advanced.getStage()).isEqualTo(2);
        assertThat(advanced.getVersion()).isEqualTo(3);
        assertThat(advanced.getLockOwner()).isNull();
        assertThat(advanced.getLockExpiresAt()).isNull();

        started.setVersion(1);
        assertThat(mapper.transitionClaimed(started, 1)).isZero();
    }

    @Test
    void otherTenantExecutionRowsAreInvisible() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(100L)).isEmpty();
        assertThat(mapper.deleteDraftByTaskId(100L)).isZero();
    }

    @Test
    void unknownResultScanIncludesTerminalRowsAndUsesCallerScope() throws SQLException {
        insertParent(7L, 100L, "COMPLETED");
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);
        mapper.freezeDraftRows(100L, 500L);
        executeRaw("UPDATE pull_task_group_execution SET execution_status = 4 "
                + "WHERE id = " + row.getId());
        executeRaw("INSERT INTO pull_task_account_action "
                + "(tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, "
                + "command_id, submitted_at, created_at, updated_at) VALUES "
                + "(7, 100, " + row.getId()
                + ", 2, 11, 22, 5, 'cmd-unknown', 400, 100, 400)");
        TenantContext.clear();

        assertThat(mapper.selectUnknownResultCandidates(
                unknownCriteria("GROUP_MARKETING", "OLD_LINK"))).isEmpty();
        assertThat(mapper.selectUnknownResultCandidates(
                unknownCriteria("STANDARD", "NORMAL_LINK")))
                .extracting(PullTaskGroupExecution::getId)
                .containsExactly(row.getId());
    }

    private PullTaskGroupExecution draft(long taskId, int seq, String link, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setGroupLinkId(9000L);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setGroupJid("120363000000000000@g.us");
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName("material-" + fileIndex + ".txt");
        row.setTotalLineCount(10);
        row.setValidMemberCount(8);
        row.setInvalidLineCount(1);
        row.setDuplicateLineCount(1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static PullTaskExecutionClaimCriteria claimCriteria(
            int limit, long now, String lockOwner, long lockExpiresAt) {
        return new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(
                        limit, now, lockOwner, lockExpiresAt),
                List.of(
                        new PullTaskExecutionClaimState(
                                PullTaskExecutionStatus.WAIT_START.code(),
                                List.of(PullTaskExecutionStage.LINK_VALIDATION.code())),
                        new PullTaskExecutionClaimState(
                                PullTaskExecutionStatus.EXECUTING.code(),
                                List.of(PullTaskExecutionStage.LINK_VALIDATION.code(),
                                        PullTaskExecutionStage.MANAGER_JOIN.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name()));
    }

    private static PullTaskUnknownReconciliationCriteria unknownCriteria(
            String taskType, String mode) {
        return new PullTaskUnknownReconciliationCriteria(
                new PullTaskUnknownReconciliationCriteria.Scope(10, 500L),
                List.of(PullTaskExecutionStatus.WAIT_START.code(),
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        PullTaskExecutionStatus.COMPLETED.code(),
                        PullTaskExecutionStatus.FAILED.code(),
                        PullTaskExecutionStatus.ABANDONED.code()),
                new PullTaskUnknownReconciliationCriteria.Parent(taskType, mode),
                new PullTaskUnknownReconciliationCriteria.Facts(
                        new PullTaskUnknownReconciliationCriteria.Action(
                                PullTaskActionStatus.SUBMITTED.code(),
                                PullTaskActionStatus.UNKNOWN.code()),
                        new PullTaskUnknownReconciliationCriteria.Call(
                                PullTaskPullCallStatus.SUBMITTED.code(),
                                PullTaskPullCallStatus.UNKNOWN.code()),
                        new PullTaskUnknownReconciliationCriteria.Material(
                                PullTaskMaterialPullStatus.SUBMITTED.code(),
                                PullTaskMaterialPullStatus.UNKNOWN.code(),
                                PullTaskMaterialAdminStatus.SUBMITTED.code(),
                                PullTaskMaterialAdminStatus.UNKNOWN.code()),
                        new PullTaskUnknownReconciliationCriteria.Account(
                                PullTaskGroupAccountMembershipStatus.JOINING.code(),
                                PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                                PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
                                PullTaskGroupAccountAdminStatus.UNKNOWN.code())));
    }

    private static PullTaskManagerJoinResultTransition managerJoinSuccess(long executionId, int version) {
        return new PullTaskManagerJoinResultTransition(
                executionId,
                100L,
                version,
                new PullTaskManagerJoinResultTransition.Expected(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.MANAGER_JOIN.code()),
                new PullTaskManagerJoinResultTransition.Target(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                        "120363verified@g.us", null, null, null, 0L, null),
                700L);
    }

    private void executeRaw(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void insertParent(long tenantId, long taskId, String status) {
        try {
            executeRaw("INSERT INTO pull_task "
                    + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                    + "created_at, updated_at) VALUES (" + taskId + ", " + tenantId
                    + ", 'STANDARD', 'task', 'NORMAL_LINK', '" + status
                    + "', '{}', 100, 100)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_execution_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupExecutionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupExecutionMapper pullTaskGroupExecutionMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupExecutionMapper.class);
        }
    }
}
