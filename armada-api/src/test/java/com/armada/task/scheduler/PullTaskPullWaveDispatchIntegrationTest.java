package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** 使用真实 Mapper XML 验证同波五个调用不等待前序回执而按持久化时钟派发。 */
@SpringJUnitConfig(PullTaskPullWaveDispatchIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWaveDispatchIntegrationTest {

    private static final long TASK_ID = 100L;
    private static final long PULLER_ACCOUNT_ID = 902L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private PullTaskPullWaveProgressService waveProgress;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskPullCallParticipantResultService callbackFixture;
    @Autowired private PullTaskPullExecutionProcessor processor;

    private long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService, callbackFixture);
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        insertParentAndSetting();
        PullTaskGroupExecution execution = draft();
        executionMapper.insertDraft(execution);
        executionMapper.freezeDraftRows(TASK_ID, 500L);
        executionId = execution.getId();
        execute("UPDATE pull_task_group_execution SET execution_status=2, stage="
                + PullTaskExecutionStage.PULL_EXECUTION.code()
                + ", version=6, group_jid='120363group@g.us' WHERE id=" + executionId);
        materialMapper.batchInsert(materials(5));
        insertPuller();
        ProtocolAccountRef puller = protocolPuller();
        when(accountLookup.findEligiblePullerProtocolRefs(anyList())).thenReturn(List.of(puller));
        AtomicInteger commandSeq = new AtomicInteger();
        when(outboxService.enqueuePullTaskBatchAddCommands(anyList()))
                .thenAnswer(invocation -> new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100",
                        List.of("cmd-wave-" + commandSeq.incrementAndGet()), 1));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void dispatchesFiveCallsOnScheduleWhileEarlierCallsRemainSubmitted() {
        PullTaskGroupExecution first = claim("worker-1", 1_000L, 6_000L);
        assertThat(processor.process(first, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(materialMapper.selectByExecution(executionId))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsExactly(
                        PullTaskMaterialPullStatus.SUBMITTED.code(),
                        PullTaskMaterialPullStatus.UNCONSUMED.code(),
                        PullTaskMaterialPullStatus.UNCONSUMED.code(),
                        PullTaskMaterialPullStatus.UNCONSUMED.code(),
                        PullTaskMaterialPullStatus.UNCONSUMED.code());
        PullTaskPullWave dispatching = waveMapper.selectActiveByExecution(
                executionId, List.of(
                        PullTaskPullWaveStatus.DISPATCHING.code(),
                        PullTaskPullWaveStatus.COLLECTING.code()));

        waveProgress.wakeCollecting(7L, executionId, dispatching.getId(), 2_000L);

        assertThat(waveMapper.selectById(dispatching.getId()).getNextCallSeq()).isEqualTo(2);
        assertThat(waveMapper.selectById(dispatching.getId()).getNextDispatchAt())
                .isEqualTo(11_000L);
        assertThat(executionMapper.selectById(executionId).getNextRunAt())
                .isEqualTo(11_000L);

        for (long now : List.of(11_000L, 21_000L, 31_000L, 41_000L)) {
            PullTaskGroupExecution candidate = claim("worker-1", now, now + 5_000L);
            assertThat(processor.process(candidate, "worker-1", now))
                    .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        }

        TenantContext.set(7L);
        List<PullTaskPullCall> calls = callMapper.selectByExecution(executionId);
        assertThat(calls).hasSize(5)
                .extracting(PullTaskPullCall::getCallStatus)
                .containsExactly(
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.SUBMITTED.code());
        assertThat(calls).extracting(PullTaskPullCall::getSubmittedAt)
                .containsExactly(1_000L, 11_000L, 21_000L, 31_000L, 41_000L);
        assertThat(materialMapper.selectByExecution(executionId))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.SUBMITTED.code());
        PullTaskPullWave wave = waveMapper.selectActiveByExecution(
                executionId, List.of(
                        PullTaskPullWaveStatus.DISPATCHING.code(),
                        PullTaskPullWaveStatus.COLLECTING.code()));
        assertThat(wave.getWaveStatus()).isEqualTo(PullTaskPullWaveStatus.COLLECTING.code());
        assertThat(wave.getDispatchCompletedAt()).isEqualTo(41_000L);
        assertThat(executionMapper.selectById(executionId).getNextRunAt()).isEqualTo(41_000L);
        verifyNoInteractions(callbackFixture);
    }

    @Test
    void restrictionDetectedAfterStickyBindingPreventsOutboxCommand() {
        when(accountLookup.findEligiblePullerProtocolRefs(anyList()))
                .thenReturn(List.of(protocolPuller()))
                .thenReturn(List.of());

        PullTaskGroupExecution candidate = claim("worker-1", 1_000L, 6_000L);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        TenantContext.set(7L);
        assertThat(callMapper.selectByExecution(executionId))
                .hasSize(5)
                .allSatisfy(call -> {
                    assertThat(call.getCallStatus())
                            .isEqualTo(PullTaskPullCallStatus.PLANNED.code());
                    assertThat(call.getCommandId()).isNull();
                });
        assertThat(materialMapper.selectByExecution(executionId))
                .extracting(PullTaskMaterialMember::getPullStatus)
                .containsOnly(PullTaskMaterialPullStatus.UNCONSUMED.code());
        verifyNoInteractions(outboxService);
    }

    private PullTaskGroupExecution claim(String owner, long now, long expiresAt) {
        TenantContext.clear();
        assertThat(executionMapper.claimDue(new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(1, now, owner, expiresAt),
                List.of(new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.PULL_EXECUTION.code()))),
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())))).isEqualTo(1);
        return executionMapper.selectClaimed(owner, now).get(0);
    }

    private void insertParentAndSetting() throws SQLException {
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(100, 7, 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, created_at, updated_at) "
                + "VALUES (7, 100, 1, 1, 1, 1, 10, 1, 0, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
    }

    private void insertPuller() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(TASK_ID);
        row.setGroupExecutionId(executionId);
        row.setAccountId(PULLER_ACCOUNT_ID);
        row.setAccountPhone("8613800000902");
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(100L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        accountMapper.insert(row);
        accountMapper.updateMembership(
                row.getId(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 100L, 100L);
    }

    private List<PullTaskMaterialMember> materials(int count) {
        List<PullTaskMaterialMember> rows = new ArrayList<>();
        for (int seq = 1; seq <= count; seq++) {
            PullTaskMaterialMember row = new PullTaskMaterialMember();
            row.setGroupExecutionId(executionId);
            row.setMemberSeq(seq);
            row.setSourceLineNo(seq);
            row.setNormalizedPhone("861390000" + String.format("%04d", seq));
            row.setAdminRequired(0);
            row.setCreatedAt(100L);
            row.setUpdatedAt(100L);
            rows.add(row);
        }
        return rows;
    }

    private PullTaskGroupExecution draft() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(TASK_ID);
        row.setSeq(1);
        row.setGroupLinkId(9_001L);
        row.setNormalizedLink("chat.whatsapp.com/AAAA");
        row.setInviteCode("AAAA");
        row.setSourceLinkLineNo(1);
        row.setSourceFileIndex(1);
        row.setSourceFileName("material.txt");
        row.setTotalLineCount(5);
        row.setValidMemberCount(5);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static ProtocolAccountRef protocolPuller() {
        return new ProtocolAccountRef(
                PULLER_ACCOUNT_ID, ProtocolBackend.WEB,
                "puller-902", "8613800000902");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_wave_dispatch_test");
        }

        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml");
        }

        @Bean SqlSessionTemplate template(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean PullTaskGroupAccountMapper accountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean PullTaskPullWaveProgressService waveProgress(
                PullTaskPullWaveMapper waveMapper,
                PullTaskGroupExecutionMapper executionMapper) {
            return new PullTaskPullWaveProgressService(waveMapper, executionMapper);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean PullTaskPullCallParticipantResultService callbackFixture() {
            return mock(PullTaskPullCallParticipantResultService.class);
        }

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stationSelection(
                PullTaskGroupAccountMapper accountMapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStationSelectionService(accountMapper, lookup);
        }

        @Bean PullTaskPullWavePlanningSelection waveSelection(
                PullTaskStationSelectionService stations,
                PullTaskBatchSizeSelector batches) {
            return new PullTaskPullWavePlanningSelection(stations, batches);
        }

        @Bean PullTaskPullWavePlanningResources waveResources(
                PullTaskGroupExecutionMapper executions,
                PullTaskPullWaveMapper waves,
                PullTaskPullCallMapper calls,
                PullTaskPullCallMemberAttemptMapper attempts,
                PullTaskPullWavePlanningSelection selection) {
            return new PullTaskPullWavePlanningResources(
                    executions, waves, calls, attempts, selection);
        }

        @Bean PullTaskPullWavePlanningTransactionService wavePlanning(
                PullTaskMapper tasks,
                PullTaskStandardSettingMapper settings,
                PullTaskMaterialMemberMapper materials,
                PullTaskGroupAccountMapper accounts,
                PullTaskPullWavePlanningResources resources) {
            return new PullTaskPullWavePlanningTransactionService(
                    tasks, settings, materials, accounts, resources);
        }

        @Bean PullTaskStickyPullerTransactionService stickyPullers(
                PullTaskGroupExecutionMapper executions,
                PullTaskGroupAccountMapper accounts,
                PullTaskPullCallMapper calls,
                PullTaskPullCallMemberAttemptMapper attempts,
                AccountProtocolLookupService lookup) {
            return new PullTaskStickyPullerTransactionService(
                    executions, accounts, calls, attempts, lookup);
        }

        @Bean PullTaskOperationDelayPolicy delayPolicy() {
            return new PullTaskOperationDelayPolicy(() -> 4_000L);
        }

        @Bean PullTaskBatchAddPersistence batchPersistence(
                PullTaskGroupExecutionMapper executions,
                PullTaskPullWaveMapper waves,
                PullTaskPullCallMapper calls,
                PullTaskPullCallMemberAttemptMapper attempts) {
            return new PullTaskBatchAddPersistence(executions, waves, calls, attempts);
        }

        @Bean PullTaskBatchAddResources batchResources(
                PullTaskBatchAddPersistence persistence,
                AccountProtocolLookupService lookup,
                ProtocolCommandOutboxService outbox,
                PullTaskOperationDelayPolicy delayPolicy) {
            return new PullTaskBatchAddResources(persistence, lookup, outbox, delayPolicy);
        }

        @Bean PullTaskBatchAddProcessor batchProcessor(
                PullTaskMapper tasks,
                PullTaskStandardSettingMapper settings,
                PullTaskGroupAccountMapper accounts,
                PullTaskMaterialMemberMapper materials,
                PullTaskBatchAddResources resources) {
            return new PullTaskBatchAddProcessor(new PullTaskBatchAddTransactionService(
                    tasks, settings, accounts, materials, resources));
        }

        @Bean PullTaskPullerStationContactProcessor contacts() {
            PullTaskPullerStationContactProcessor contacts =
                    mock(PullTaskPullerStationContactProcessor.class);
            when(contacts.process(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(PullTaskStationContactStepResult.CALL_READY);
            return contacts;
        }

        @Bean PullTaskPullWaveSettlementResources settlementResources(
                PullTaskMapper tasks,
                PullTaskGroupExecutionMapper executions,
                PullTaskPullWaveMapper waves,
                PullTaskPullCallMemberAttemptMapper attempts,
                PullTaskMaterialMemberMapper materials) {
            return new PullTaskPullWaveSettlementResources(
                    tasks, executions, waves, attempts, materials);
        }

        @Bean PullTaskPullWaveSettlementTransactionService settlement(
                PullTaskPullWaveSettlementResources resources,
                PullTaskPullWavePlanningTransactionService planning,
                PullTaskExecutionDispatchProperties properties) {
            return new PullTaskPullWaveSettlementTransactionService(
                    resources, planning, properties,
                    mock(PullTaskGroupProfileDispatcher.class));
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        @Bean PullTaskPullExecutionDispatchResources dispatchResources(
                PullTaskPullWavePlanningTransactionService waves,
                PullTaskStickyPullerTransactionService pullers,
                PullTaskPullWaveSettlementTransactionService settlement,
                PullTaskPullerStationContactProcessor contacts,
                PullTaskBatchAddProcessor batch) {
            return new PullTaskPullExecutionDispatchResources(
                    waves, pullers, settlement, contacts, batch);
        }

        @Bean PullTaskPullExecutionProcessor processor(
                PullTaskPullExecutionDispatchResources resources) {
            return new PullTaskPullExecutionProcessor(
                    resources, mock(PullTaskCreatorLeaveProcessor.class),
                    mock(PullTaskClosingTransactionService.class));
        }
    }
}
