package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.group.model.vo.GroupPoolResourceVO;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.task.service.PullTaskStandardLifecycleService;
import com.armada.task.service.impl.PullTaskStandardLifecycleServiceImpl;
import com.armada.task.service.impl.PullTaskStandardLifecycleResources;
import com.armada.task.service.impl.PullTaskLifecyclePullResources;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** EX-01 单租户短事务、父任务并发槽位与旧阶段本地推进集成测试。 */
@SpringJUnitConfig(PullTaskExecutionTransactionServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskExecutionTransactionServiceTest {

    private static final String LINK = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final AtomicReference<Runnable> BEFORE_BIND = new AtomicReference<>();

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMapper taskMapper;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskExecutionTransactionService transactionService;
    @Autowired private GroupFolderService groupFolderService;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        org.mockito.Mockito.reset(groupFolderService);
        BEFORE_BIND.set(null);
        PullTaskNormalLinkH2Support.resetSchemaWithProtocolOutbox(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void conditionalSlotClaimAllowsOnlyOneExecutionRowToEnterRunningState() throws SQLException {
        seedParent(100L, "EXECUTING");
        insertAndFreeze(100L, 1, LINK);
        insertAndFreeze(100L, 2, "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB");
        List<PullTaskGroupExecution> claimed = claim(10, "worker-1", 1_000L);

        TenantContext.set(99L);
        PullTaskExecutionWork first = transactionService
                .prepare(claimed.get(0), "worker-1", 600L).orElseThrow();
        assertThat(transactionService.prepare(claimed.get(1), "worker-1", 600L)).isEmpty();
        assertThat(TenantContext.get()).isEqualTo(99L);

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L))
                .extracting(PullTaskGroupExecution::getExecutionStatus)
                .containsExactly(2, 1);
        assertThat(first.expectedVersion()).isEqualTo(2);
    }

    @Test
    void configuredConcurrentLimitAllowsExactlyTwoExecutionRowsToRun() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task_standard_setting SET concurrent_group_count = 2 "
                + "WHERE task_id = 100");
        insertAndFreeze(100L, 1, LINK);
        insertAndFreeze(100L, 2, "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB");
        insertAndFreeze(100L, 3, "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC");
        List<PullTaskGroupExecution> claimed = claim(10, "worker-1", 1_000L);

        assertThat(transactionService.prepare(claimed.get(0), "worker-1", 600L)).isPresent();
        assertThat(transactionService.prepare(claimed.get(1), "worker-1", 600L)).isPresent();
        assertThat(transactionService.prepare(claimed.get(2), "worker-1", 600L)).isEmpty();

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L))
                .extracting(PullTaskGroupExecution::getExecutionStatus)
                .containsExactly(2, 2, 1);
    }

    @Test
    void staleCandidateStatusDoesNotConsumeParentSlotToken() throws SQLException {
        seedParent(100L, "EXECUTING");
        insertAndFreeze(100L, 1, LINK);
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);
        execute("UPDATE pull_task_group_execution SET execution_status = 5 WHERE id = "
                + claimed.getId());

        assertThat(transactionService.prepare(claimed, "worker-1", 600L)).isEmpty();

        TenantContext.set(7L);
        assertThat(taskVersion(100L)).isEqualTo(1);
    }

    @Test
    void concurrentConditionalSlotClaimsStartExactlyOneExecutionRow() throws Exception {
        seedParent(100L, "EXECUTING");
        insertAndFreeze(100L, 1, LINK);
        insertAndFreeze(100L, 2, "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB");
        List<PullTaskGroupExecution> claimed = claim(10, "worker-1", 1_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<PullTaskExecutionWork>> first = executor.submit(
                    () -> prepareTogether(claimed.get(0), ready, start));
            Future<Optional<PullTaskExecutionWork>> second = executor.submit(
                    () -> prepareTogether(claimed.get(1), ready, start));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(3, TimeUnit.SECONDS),
                            second.get(3, TimeUnit.SECONDS)))
                    .filteredOn(Optional::isPresent)
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(100L))
                .extracting(PullTaskGroupExecution::getExecutionStatus)
                .containsExactlyInAnyOrder(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStatus.WAIT_START.code());
    }

    @Test
    void legacyLinkValidationAdvancesLocallyAndReleasesLease() throws SQLException {
        PullTaskExecutionWork work = prepareLegacySingle("worker-1");

        assertThat(transactionService.advanceLegacyLinkValidation(work, 700L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectByTaskId(100L).get(0);
        assertThat(saved.getExecutionStatus()).isEqualTo(2);
        assertThat(saved.getStage()).isEqualTo(2);
        assertThat(saved.getReasonCode()).isNull();
        assertThat(saved.getLockOwner()).isNull();
    }

    @Test
    void newGroupRowWithoutALinkCanClaimItsSlotAndStayAtGroupCreate() throws SQLException {
        seedParent(100L, "EXECUTING");
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(100L);
        row.setSeq(1);
        row.setSourceFileIndex(1);
        row.setSourceFileName("material-1.txt");
        row.setStage(PullTaskExecutionStage.GROUP_CREATE.code());
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        executionMapper.freezeDraftRows(100L, 500L);
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        PullTaskExecutionWork work = transactionService
                .prepare(claimed, "worker-1", 600L).orElseThrow();

        assertThat(work.normalizedLink()).isNull();
        assertThat(work.expectedVersion()).isEqualTo(2);
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(row.getId());
        assertThat(saved.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
        assertThat(saved.getCreateStep()).isEqualTo(1);
    }

    @Test
    void directEntryClaimsFolderAndConcurrencySlotAtItsOwnStage() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode='DIRECT_LINK' WHERE id=100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id=18 WHERE task_id=100");
        insertUnboundAndFreeze(100L, 1);
        execute("UPDATE pull_task_group_execution SET stage=10 WHERE task_id=100");
        GroupPoolResourceVO resource = new GroupPoolResourceVO(901L, "120363000000901@g.us",
                "chat.whatsapp.com/POOL01", "POOL01");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(resource));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 901L)).thenReturn(resource);
        PullTaskGroupExecution claimed = claim(1, "worker-direct", 1_000L).get(0);

        assertThat(transactionService.prepare(claimed, "worker-direct", 600L)).isPresent();

        TenantContext.set(7L);
        assertThat(executionMapper.selectById(claimed.getId())).satisfies(saved -> {
            assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.DIRECT_PULLER_JOIN.code());
            assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
            assertThat(saved.getGroupLinkId()).isEqualTo(901L);
        });
    }

    @Test
    void unboundTxtClaimsAGroupFromTheCurrentFolderAtRuntime() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode = 'RESOURCE_POOL' WHERE id = 100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id = 18 "
                + "WHERE task_id = 100");
        insertUnboundAndFreeze(100L, 1);
        GroupPoolResourceVO resource = new GroupPoolResourceVO(
                901L, "120363000000901@g.us",
                "chat.whatsapp.com/POOL01", "POOL01");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(resource));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 901L))
                .thenReturn(resource);
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        PullTaskExecutionWork work = transactionService
                .prepare(claimed, "worker-1", 600L).orElseThrow();

        assertThat(work.normalizedLink()).isEqualTo("chat.whatsapp.com/POOL01");
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(claimed.getId());
        assertThat(saved.getGroupLinkId()).isEqualTo(901L);
        assertThat(saved.getGroupJid()).isEqualTo("120363000000901@g.us");
        assertThat(saved.getExecutionStatus()).isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        verify(groupFolderService).requireUsableResourceForUpdate(18L, 901L);
    }

    @Test
    void pastedLinkRetryClaimsANewGroupAndDoesNotReuseTaskHistory() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id = 18 "
                + "WHERE task_id = 100");
        insertAndFreeze(100L, 1, LINK);
        execute("UPDATE pull_task_group_execution "
                + "SET group_jid = '120363000000901@g.us', execution_status = 5 "
                + "WHERE task_id = 100 AND seq = 1");
        insertUnboundRetryAndFreeze(100L, 2);
        GroupPoolResourceVO used = new GroupPoolResourceVO(
                901L, "120363000000901@g.us",
                "chat.whatsapp.com/POOL01", "POOL01");
        GroupPoolResourceVO next = new GroupPoolResourceVO(
                902L, "120363000000902@g.us",
                "chat.whatsapp.com/POOL02", "POOL02");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(used, next));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 902L))
                .thenReturn(next);
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        PullTaskExecutionWork work = transactionService
                .prepare(claimed, "worker-1", 600L).orElseThrow();

        assertThat(work.normalizedLink()).isEqualTo("chat.whatsapp.com/POOL02");
        TenantContext.set(7L);
        PullTaskGroupExecution saved = executionMapper.selectById(claimed.getId());
        assertThat(saved.getGroupLinkId()).isEqualTo(902L);
        assertThat(saved.getGroupJid()).isEqualTo("120363000000902@g.us");
        verify(groupFolderService).requireUsableResourceForUpdate(18L, 902L);
    }

    @Test
    void pastedLinkTaskKeepsItsSourceFolderProtectedWhileActive() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id = 18 "
                + "WHERE task_id = 100");

        assertThat(executionMapper.countTasksUsingFolders(
                List.of(18L), List.of(PullTaskStandardStatus.EXECUTING.name())))
                .isEqualTo(1);
    }

    @Test
    void emptyFolderEndsParentAndAbandonsPendingExecution() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode = 'RESOURCE_POOL' WHERE id = 100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id = 18 "
                + "WHERE task_id = 100");
        insertUnboundAndFreeze(100L, 1);
        when(groupFolderService.usableResources(18L)).thenReturn(List.of());
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        assertThat(transactionService.prepare(claimed, "worker-1", 600L)).isEmpty();

        TenantContext.set(7L);
        assertThat(taskMapper.selectLifecycle(100L).getStatus())
                .isEqualTo(PullTaskStandardStatus.ENDED.name());
        assertThat(taskMapper.selectLifecycle(100L).getBlockingReason())
                .isEqualTo("群资源已耗尽，任务结束");
        assertThat(taskMapper.selectLifecycle(100L).getFinishedAt()).isNotNull();
        assertThat(executionMapper.selectById(claimed.getId()).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.ABANDONED.code());
    }

    @Test
    void historyWithMissingJidCannotBlockSelectionOfTheNextLink() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode='DIRECT_LINK' WHERE id=100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id=18 WHERE task_id=100");
        insertAndFreeze(100L, 1, LINK);
        execute("UPDATE pull_task_group_execution SET execution_status=6 WHERE task_id=100");
        insertUnboundAndFreeze(100L, 2);
        execute("UPDATE pull_task_group_execution SET stage=10 WHERE task_id=100 AND seq=2");
        GroupPoolResourceVO used = new GroupPoolResourceVO(901L, "old@g.us", LINK, "old");
        GroupPoolResourceVO next = new GroupPoolResourceVO(902L, "next@g.us",
                "chat.whatsapp.com/NEXT", "NEXT");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(used, next));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 902L)).thenReturn(next);
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        PullTaskExecutionWork work = transactionService.prepare(claimed, "worker-1", 600L)
                .orElseThrow();

        assertThat(work.normalizedLink()).isEqualTo(next.normalizedLink());
        TenantContext.set(7L);
        assertThat(executionMapper.selectById(claimed.getId()).getGroupLinkId()).isEqualTo(902L);
        assertThat(executionMapper.selectByTaskId(100L).get(0).getGroupJid()).isNull();
        assertThat(executionMapper.selectByTaskId(100L).get(0).getNormalizedLink()).isEqualTo(LINK);
    }

    @Test
    void fullConcurrencySlotDoesNotEndParentBeforeAnotherTxtFinishes() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode = 'RESOURCE_POOL' WHERE id = 100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id = 18 "
                + "WHERE task_id = 100");
        insertUnboundAndFreeze(100L, 1);
        insertUnboundAndFreeze(100L, 2);
        execute("UPDATE pull_task_group_execution SET execution_status = 2, stage = 6 "
                + "WHERE task_id = 100 AND seq = 1");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of());
        PullTaskGroupExecution claimed = claim(1, "worker-1", 1_000L).get(0);

        assertThat(transactionService.prepare(claimed, "worker-1", 600L)).isEmpty();

        TenantContext.set(7L);
        assertThat(taskMapper.selectLifecycle(100L).getStatus())
                .isEqualTo(PullTaskStandardStatus.EXECUTING.name());
    }

    @Test
    void bindingConflictAfterSelectionTriesNextCandidateInTheSameTransaction() throws SQLException {
        seedResourcePool();
        insertAndFreeze(100L, 1, "chat.whatsapp.com/OLD");
        execute("UPDATE pull_task_group_execution SET execution_status=6 WHERE task_id=100");
        PullTaskGroupExecution candidate = pendingResourceCandidate(2);
        GroupPoolResourceVO first = resource(901L, LINK);
        GroupPoolResourceVO next = resource(902L, "chat.whatsapp.com/NEXT");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(first, next));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 902L)).thenReturn(next);
        // 在真实筛选 SELECT 之后、真实绑定 UPDATE 之前提交另一条记录，触发数据库唯一键冲突。
        BEFORE_BIND.set(() -> new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE pull_task_group_execution SET normalized_link=? WHERE task_id=100 AND seq=1", LINK));

        assertThat(transactionService.prepare(candidate, "worker-1", 600L).orElseThrow()
                .normalizedLink()).isEqualTo(next.normalizedLink());

        TenantContext.set(7L);
        assertThat(executionMapper.selectById(candidate.getId()).getExecutionStatus()).isEqualTo(2);
        assertThat(executionMapper.selectById(candidate.getId()).getGroupLinkId()).isEqualTo(902L);
        assertThat(taskVersion(100L)).isEqualTo(2);
    }

    @Test
    void allCandidatesConflictingAtBindEndsTaskInsteadOfRetryingTheSameHead() throws SQLException {
        seedResourcePool();
        insertAndFreeze(100L, 1, "chat.whatsapp.com/OLD");
        execute("UPDATE pull_task_group_execution SET execution_status=6 WHERE task_id=100");
        PullTaskGroupExecution candidate = pendingResourceCandidate(2);
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(resource(901L, LINK)));
        BEFORE_BIND.set(() -> new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE pull_task_group_execution SET normalized_link=? WHERE task_id=100 AND seq=1", LINK));

        assertThat(transactionService.prepare(candidate, "worker-1", 600L)).isEmpty();

        TenantContext.set(7L);
        assertThat(taskMapper.selectLifecycle(100L).getStatus()).isEqualTo("ENDED");
        assertThat(executionMapper.selectById(candidate.getId()).getExecutionStatus()).isEqualTo(6);
        assertThat(executionMapper.selectById(candidate.getId()).getNormalizedLink()).isNull();
        assertThat(claim(10, "worker-next", 2_000L)).isEmpty();
    }

    @Test
    void activeLinkWithoutJidInAnotherTaskIsExcluded() throws SQLException {
        seedParent(200L, "PAUSED");
        insertAndFreeze(200L, 1, LINK);
        seedResourcePool();
        PullTaskGroupExecution candidate = pendingResourceCandidate(1);
        GroupPoolResourceVO next = resource(902L, "chat.whatsapp.com/NEXT");
        when(groupFolderService.usableResources(18L)).thenReturn(List.of(resource(901L, LINK), next));
        when(groupFolderService.requireUsableResourceForUpdate(18L, 902L)).thenReturn(next);

        assertThat(transactionService.prepare(candidate, "worker-1", 600L).orElseThrow()
                .normalizedLink()).isEqualTo(next.normalizedLink());
    }

    @Test
    void assignedLinkQueryRespectsTenantAndIncludesTerminalRows() throws SQLException {
        seedParent(100L, "EXECUTING");
        insertAndFreeze(100L, 1, LINK);
        execute("UPDATE pull_task_group_execution SET execution_status=6 WHERE task_id=100");

        assertThat(executionMapper.selectAssignedLinks(100L, List.of(LINK))).containsExactly(LINK);
        TenantContext.set(8L);
        assertThat(executionMapper.selectAssignedLinks(100L, List.of(LINK))).isEmpty();
    }

    private void seedResourcePool() throws SQLException {
        seedParent(100L, "EXECUTING");
        execute("UPDATE pull_task SET creation_mode='RESOURCE_POOL' WHERE id=100");
        execute("UPDATE pull_task_standard_setting SET source_group_folder_id=18 WHERE task_id=100");
    }

    private PullTaskGroupExecution pendingResourceCandidate(int seq) {
        insertUnboundAndFreeze(100L, seq);
        return claim(1, "worker-1", 1_000L).get(0);
    }

    private static GroupPoolResourceVO resource(long id, String link) {
        return new GroupPoolResourceVO(id, id + "@g.us", link,
                link.substring(link.lastIndexOf('/') + 1));
    }

    private PullTaskExecutionWork prepareLegacySingle(String lockOwner) throws SQLException {
        seedParent(100L, "EXECUTING");
        insertAndFreeze(100L, 1, LINK);
        execute("UPDATE pull_task_group_execution SET stage = 1 WHERE task_id = 100");
        PullTaskGroupExecution claimed = claim(1, lockOwner, 1_000L).get(0);
        return transactionService.prepare(claimed, lockOwner, 600L).orElseThrow();
    }

    private List<PullTaskGroupExecution> claim(int limit, String lockOwner, long leaseUntil) {
        TenantContext.clear();
        executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(
                        limit, 600L, lockOwner, leaseUntil),
                List.of(
                        new PullTaskExecutionClaimState(
                                PullTaskExecutionStatus.WAIT_START.code(),
                                List.of(PullTaskExecutionStage.LINK_VALIDATION.code(),
                                        PullTaskExecutionStage.DIRECT_PULLER_JOIN.code(),
                                        PullTaskExecutionStage.MANAGER_JOIN.code(),
                                        PullTaskExecutionStage.GROUP_CREATE.code())),
                        new PullTaskExecutionClaimState(
                                PullTaskExecutionStatus.EXECUTING.code(),
                                List.of(PullTaskExecutionStage.LINK_VALIDATION.code(),
                                        PullTaskExecutionStage.MANAGER_JOIN.code(),
                                        PullTaskExecutionStage.GROUP_CREATE.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
        return executionMapper.selectClaimed(lockOwner, 600L);
    }

    private void seedParent(long taskId, String status) throws SQLException {
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, created_at, updated_at) "
                + "VALUES (" + taskId + ", 7, 'STANDARD', 'task', 'NORMAL_LINK', '" + status
                + "', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (7, " + taskId + ", 0, 1, 1, 2, 1, 1, 0, 1, 0, 1, "
                + "88, 89, 90, 'manager', 'puller', 'station', 100, 100)");
    }

    private void insertAndFreeze(long taskId, int seq, String link) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setGroupLinkId(9_000L + seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(seq);
        row.setSourceFileName("material-" + seq + ".txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        executionMapper.freezeDraftRows(taskId, 500L);
    }

    private void insertUnboundAndFreeze(long taskId, int seq) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        row.setSourceFileIndex(seq);
        row.setSourceFileName("material-" + seq + ".txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        executionMapper.freezeDraftRows(taskId, 500L);
    }

    private void insertUnboundRetryAndFreeze(long taskId, int seq) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setAttemptNo(2);
        row.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        row.setSourceFileIndex(seq);
        row.setSourceFileName("material-" + seq + ".txt");
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        executionMapper.freezeDraftRows(taskId, 500L);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int taskVersion(long taskId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT version FROM pull_task WHERE id = ?")) {
            statement.setLong(1, taskId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private Optional<PullTaskExecutionWork> prepareTogether(
            PullTaskGroupExecution candidate,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发槽位测试启动超时");
        }
        return transactionService.prepare(candidate, "worker-1", 600L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_execution_transaction_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            SqlSessionFactory factory = PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskMemberQueryMapper.xml");
            factory.getConfiguration().addInterceptor(new BeforeBindInterceptor());
            return factory;
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean
        PullTaskExecutionTransactionService transactionService(PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskGroupExecutionMapper executionMapper,
                GroupFolderService groupFolderService,
                PullTaskStandardLifecycleService lifecycleService) {
            return new PullTaskExecutionTransactionService(
                    taskMapper, settingMapper, executionMapper, groupFolderService, lifecycleService);
        }

        @Bean
        PullTaskStandardLifecycleService lifecycleService(
                PullTaskMapper taskMapper, PullTaskGroupExecutionMapper executionMapper,
                SqlSessionTemplate template) {
            PullTaskLifecyclePullResources pull = new PullTaskLifecyclePullResources(
                    template.getMapper(PullTaskGroupAccountMapper.class),
                    template.getMapper(PullTaskPullCallMapper.class),
                    template.getMapper(PullTaskPullCallMemberAttemptMapper.class),
                    template.getMapper(PullTaskMaterialMemberMapper.class),
                    template.getMapper(PullTaskPullWaveMapper.class),
                    mock(GroupDataPackageTaskProjectionService.class));
            PullTaskStandardLifecycleResources resources = new PullTaskStandardLifecycleResources(
                    executionMapper, template.getMapper(PullTaskAccountActionMapper.class),
                    template.getMapper(PullTaskMemberQueryMapper.class), pull,
                    mock(ProtocolCommandOutboxService.class), mock(PullTaskExecutionDispatchTrigger.class), org.mockito.Mockito.mock(com.armada.task.scheduler.PullTaskCreatorDeletionTransactionService.class));
            return new PullTaskStandardLifecycleServiceImpl(taskMapper, resources,
                    mock(PullTaskParentCompletionService.class), () -> 600L);
        }

        @Bean
        GroupFolderService groupFolderService() {
            return mock(GroupFolderService.class);
        }
    }

    /** 只安排竞争写入时序，所有业务 SELECT/UPDATE 与唯一约束仍由真实 H2 Mapper 执行。 */
    @Intercepts(@Signature(type = Executor.class, method = "update",
            args = {MappedStatement.class, Object.class}))
    public static class BeforeBindInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith(".bindGroupAndStartClaimed")) {
                Runnable competingWrite = BEFORE_BIND.getAndSet(null);
                if (competingWrite != null) {
                    competingWrite.run();
                }
            }
            return invocation.proceed();
        }
    }
}
