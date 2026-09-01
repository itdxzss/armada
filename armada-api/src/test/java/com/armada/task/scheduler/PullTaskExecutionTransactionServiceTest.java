package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.group.model.vo.GroupPoolResourceVO;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
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

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskMapper taskMapper;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskExecutionTransactionService transactionService;
    @Autowired private GroupFolderService groupFolderService;

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
    void emptyFolderMovesParentToWaitGroupResource() throws SQLException {
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
                .isEqualTo(PullTaskStandardStatus.WAIT_GROUP_RESOURCE.name());
        assertThat(executionMapper.selectById(claimed.getId()).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_START.code());
    }

    @Test
    void emptyFolderDoesNotPauseParentWhileAnotherTxtIsStillExecuting() throws SQLException {
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
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml");
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
                GroupFolderService groupFolderService) {
            return new PullTaskExecutionTransactionService(
                    taskMapper, settingMapper, executionMapper, groupFolderService);
        }

        @Bean
        GroupFolderService groupFolderService() {
            return mock(GroupFolderService.class);
        }
    }
}
