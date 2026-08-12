package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskPullWaveCandidate;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

/** 使用真实 Mapper XML 验证完整初始波次冻结和上一波重试候选筛选。 */
@SpringJUnitConfig(PullTaskPullWavePlanningIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWavePlanningIntegrationTest {

    private static final long TASK_ID = 100L;
    private static final int MATERIAL_COUNT = 21;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private PullTaskPullWavePlanningTransactionService service;

    private Long executionId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup);
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
        materialMapper.batchInsert(materials(MATERIAL_COUNT));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void freezesAllInitialCallsBeforeReturningTheFirstCall() {
        PullTaskPullWavePreparation result = service.prepare(
                claim("worker-1", 600L, 900L), "worker-1", 610L);

        TenantContext.set(7L);
        assertThat(result.ready()).isTrue();
        assertThat(result.call().getWaveCallSeq()).isEqualTo(1);
        assertThat(waveMapper.selectById(result.wave().getId()).getPlannedCallCount())
                .isEqualTo(5);
        assertThat(callMapper.selectByExecution(executionId))
                .extracting(PullTaskPullCall::getPlannedMaterialCount)
                .containsExactly(5, 5, 5, 5, 1);
        assertThat(callMapper.selectByExecution(executionId))
                .allSatisfy(call -> {
                    assertThat(call.getPullWaveId()).isEqualTo(result.wave().getId());
                    assertThat(call.getPullerGroupAccountId()).isNull();
                    assertThat(call.getPullerAccountId()).isNull();
                });
        assertThat(attemptsByWave(result.wave().getId())).hasSize(MATERIAL_COUNT)
                .allSatisfy(attempt -> assertThat(attempt.getPullerGroupAccountId()).isNull());
        assertThat(attemptMapper.countOpenByWave(
                result.wave().getId(),
                List.of(PullTaskParticipantAttemptStatus.PLANNED.code(),
                        PullTaskParticipantAttemptStatus.SUBMITTED.code())))
                .isEqualTo(MATERIAL_COUNT);
    }

    @Test
    void prepareReturnsExistingActiveWaveWithoutRepartitioning() {
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullWavePreparation first = service.prepare(candidate, "worker-1", 610L);

        PullTaskPullWavePreparation second = service.prepare(candidate, "worker-1", 620L);

        TenantContext.set(7L);
        assertThat(second.wave().getId()).isEqualTo(first.wave().getId());
        assertThat(second.call().getId()).isEqualTo(first.call().getId());
        assertThat(callMapper.selectByExecution(executionId)).hasSize(5);
        assertThat(attemptsByWave(first.wave().getId())).hasSize(MATERIAL_COUNT);
    }

    @Test
    void earlyFailedAggregateCannotBeSelectedAgainInsideTheActiveWave() throws SQLException {
        PullTaskGroupExecution candidate = claim("worker-1", 600L, 900L);
        PullTaskPullWavePreparation first = service.prepare(candidate, "worker-1", 610L);
        PullTaskPullCallMemberAttempt attempt = attemptsByWave(first.wave().getId()).get(0);
        execute("UPDATE pull_task_material_member SET pull_status=0, pull_call_id=NULL, "
                + "active_pull_attempt_id=NULL WHERE id=" + attempt.getParticipantRefId());

        PullTaskPullWavePreparation resumed = service.prepare(candidate, "worker-1", 620L);

        TenantContext.set(7L);
        assertThat(resumed.wave().getId()).isEqualTo(first.wave().getId());
        assertThat(attemptsByWave(first.wave().getId()))
                .filteredOn(row -> row.getParticipantRefId().equals(attempt.getParticipantRefId()))
                .hasSize(1);
        assertThat(callMapper.selectByExecution(executionId)).hasSize(5);
    }

    @Test
    void retryWaveUsesOnlyReleasedOrExplicitFailedAttemptsFromPreviousWave()
            throws SQLException {
        PullTaskPullWave wave = preparedWave();
        List<PullTaskPullCallMemberAttempt> attempts = attemptsByWave(wave.getId());
        mutate(attempts.get(0), new AttemptFact(3, "FAILED", "STARTED", "PRIVACY", 1));
        mutate(attempts.get(1), new AttemptFact(4, "UNKNOWN", "NOT_STARTED", "OFFLINE", 0));
        mutate(attempts.get(2), new AttemptFact(
                4, "UNKNOWN", "UNCERTAIN", "TIMEOUT", 0));
        mutate(attempts.get(3), new AttemptFact(3, "SUCCESS", "STARTED", null, 0));
        mutate(attempts.get(4), new AttemptFact(
                3, "UNKNOWN", "UNCERTAIN", "ROSTER_QUERY_FAILED", 0));

        List<PullTaskPullWaveCandidate> retryCandidates =
                attemptMapper.selectRetryCandidatesByWave(wave.getId(), 4L);
        assertThat(retryCandidates)
                .extracting(PullTaskPullWaveCandidate::participantRefId)
                .containsExactly(
                        attempts.get(0).getParticipantRefId(),
                        attempts.get(1).getParticipantRefId(),
                        attempts.get(2).getParticipantRefId());

        execute("UPDATE pull_task_pull_wave SET wave_status=3 WHERE id=" + wave.getId());
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id=NULL WHERE id="
                + executionId);
        PullTaskPullWave retry = service.createRetryWave(
                executionMapper.selectById(executionId), wave, retryCandidates, 2_000L);

        assertThat(retry.getWaveNo()).isEqualTo(2);
        assertThat(retry.getNextDispatchAt()).isEqualTo(2_000L);
        assertThat(callMapper.selectByExecution(executionId))
                .filteredOn(call -> retry.getId().equals(call.getPullWaveId()))
                .singleElement()
                .satisfies(call -> assertThat(attemptMapper.selectByCall(call.getId()))
                        .extracting(PullTaskPullCallMemberAttempt::getParticipantRefId)
                        .containsExactly(
                                attempts.get(0).getParticipantRefId(),
                                attempts.get(1).getParticipantRefId(),
                                attempts.get(2).getParticipantRefId()));
    }

    @Test
    void fourthExplicitFailureAndFinalUnknownAreExcludedFromRetryWave() throws SQLException {
        PullTaskPullWave wave = preparedWave();
        List<PullTaskPullCallMemberAttempt> attempts = attemptsByWave(wave.getId());
        mutate(attempts.get(0), new AttemptFact(3, "FAILED", "STARTED", "PRIVACY", 4));
        mutate(attempts.get(1), new AttemptFact(
                3, "UNKNOWN", "UNCERTAIN", "ROSTER_QUERY_FAILED", 0));

        assertThat(attemptMapper.selectRetryCandidatesByWave(wave.getId(), 4L))
                .extracting(PullTaskPullWaveCandidate::participantRefId)
                .doesNotContain(
                        attempts.get(0).getParticipantRefId(),
                        attempts.get(1).getParticipantRefId());
    }

    @Test
    void retryCandidatesKeepStableParticipantOrder() throws SQLException {
        PullTaskPullWave wave = preparedWave();
        List<PullTaskPullCallMemberAttempt> attempts = attemptsByWave(wave.getId());
        mutate(attempts.get(4), new AttemptFact(4, "UNKNOWN", "NOT_STARTED", "OFFLINE", 0));
        mutate(attempts.get(1), new AttemptFact(4, "UNKNOWN", "NOT_STARTED", "OFFLINE", 0));
        mutate(attempts.get(3), new AttemptFact(4, "UNKNOWN", "NOT_STARTED", "OFFLINE", 0));

        assertThat(attemptMapper.selectRetryCandidatesByWave(wave.getId(), 4L))
                .extracting(PullTaskPullWaveCandidate::participantRefId)
                .containsExactly(
                        attempts.get(1).getParticipantRefId(),
                        attempts.get(3).getParticipantRefId(),
                        attempts.get(4).getParticipantRefId());
    }

    @Test
    void stationAndMaterialCandidatesKeepStableOrder() throws SQLException {
        execute("UPDATE pull_task_standard_setting SET station_count_per_call=1 WHERE task_id=100");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(
                station(911L), station(912L), station(913L), station(914L), station(915L)));

        PullTaskPullWavePreparation result = service.prepare(
                claim("worker-1", 600L, 900L), "worker-1", 610L);

        TenantContext.set(7L);
        assertThat(callMapper.selectByExecution(executionId)).allSatisfy(call -> {
            List<PullTaskPullCallMemberAttempt> attempts = attemptMapper.selectByCall(call.getId());
            assertThat(attempts.get(attempts.size() - 1).getParticipantType())
                    .isEqualTo(PullTaskParticipantType.STATION.code());
            assertThat(attempts.subList(0, attempts.size() - 1))
                    .extracting(PullTaskPullCallMemberAttempt::getParticipantType)
                    .containsOnly(PullTaskParticipantType.MATERIAL.code());
        });
        assertThat(groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code()))
                .extracting(row -> row.getAccountId())
                .containsExactly(911L, 912L, 913L, 914L, 915L);
        assertThat(result.wave().getPlannedCallCount()).isEqualTo(5);
    }

    @Test
    void insufficientStationsRollBackTheWholeWaveAndEnterStationWait() throws SQLException {
        execute("UPDATE pull_task_standard_setting SET station_count_per_call=1 WHERE task_id=100");
        when(accountLookup.findOnlineNormalByGroupId(90L)).thenReturn(List.of(
                station(911L), station(912L), station(913L), station(914L)));

        PullTaskPullWavePreparation result = service.prepare(
                claim("worker-1", 600L, 900L), "worker-1", 610L);

        TenantContext.set(7L);
        assertThat(result.ready()).isFalse();
        assertThat(result.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        assertThat(callMapper.selectByExecution(executionId)).isEmpty();
        assertThat(waveMapper.selectActiveByExecution(
                executionId, List.of(1, 2))).isNull();
        assertThat(materialMapper.selectByExecution(executionId))
                .allSatisfy(row -> {
                    assertThat(row.getPullCallId()).isNull();
                    assertThat(row.getActivePullAttemptId()).isNull();
                });
        assertThat(executionMapper.selectById(executionId))
                .satisfies(saved -> {
                    assertThat(saved.getExecutionStatus())
                            .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
                    assertThat(saved.getWaitResourceType())
                            .isEqualTo(PullTaskWaitResourceType.STATION.code());
                });
    }

    private PullTaskPullWave preparedWave() {
        return service.prepare(
                claim("worker-1", 600L, 900L), "worker-1", 610L).wave();
    }

    private List<PullTaskPullCallMemberAttempt> attemptsByWave(long waveId) {
        TenantContext.set(7L);
        return callMapper.selectByExecution(executionId).stream()
                .filter(call -> Long.valueOf(waveId).equals(call.getPullWaveId()))
                .flatMap(call -> attemptMapper.selectByCall(call.getId()).stream())
                .sorted(java.util.Comparator.comparing(PullTaskPullCallMemberAttempt::getId))
                .toList();
    }

    private void mutate(PullTaskPullCallMemberAttempt attempt, AttemptFact fact)
            throws SQLException {
        String reason = fact.reasonCode() == null ? "NULL" : "'" + fact.reasonCode() + "'";
        execute("UPDATE pull_task_pull_call_member_attempt SET lifecycle_status="
                + fact.lifecycleStatus() + ", active_slot=NULL, protocol_outcome='"
                + fact.outcome() + "', execution_state='" + fact.executionState()
                + "', reason_code=" + reason + " WHERE id=" + attempt.getId());
        execute("UPDATE pull_task_material_member SET pull_status=0, pull_call_id=NULL, "
                + "active_pull_attempt_id=NULL, pull_failure_count=" + fact.failureCount()
                + " WHERE id=" + attempt.getParticipantRefId());
    }

    private PullTaskGroupExecution claim(String owner, long now, long expiresAt) {
        TenantContext.clear();
        executionMapper.claimDue(new com.armada.task.model.dto.PullTaskExecutionClaimCriteria(
                new com.armada.task.model.dto.PullTaskExecutionClaimCriteria.Lease(
                        1, now, owner, expiresAt),
                List.of(new com.armada.task.model.dto.PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.PULL_EXECUTION.code()))),
                new com.armada.task.model.dto.PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), "NORMAL_LINK",
                        PullTaskStandardStatus.EXECUTING.name())));
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
                + "VALUES (7, 100, 1, 1, 5, 5, 10, 1, 0, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
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
        row.setTotalLineCount(MATERIAL_COUNT);
        row.setValidMemberCount(MATERIAL_COUNT);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private ProtocolAccountRef station(long id) {
        return new ProtocolAccountRef(
                id, ProtocolBackend.WEB, "station-" + id, "8613800000" + id);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record AttemptFact(
            int lifecycleStatus,
            String outcome,
            String executionState,
            String reasonCode,
            long failureCount) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_wave_planning_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml");
        }

        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
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

        @Bean PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stationSelectionService(
                PullTaskGroupAccountMapper mapper,
                AccountProtocolLookupService lookup) {
            return new PullTaskStationSelectionService(mapper, lookup);
        }

        @Bean PullTaskPullWavePlanningSelection planningSelection(
                PullTaskStationSelectionService stationSelectionService,
                PullTaskBatchSizeSelector batchSizeSelector) {
            return new PullTaskPullWavePlanningSelection(
                    stationSelectionService, batchSizeSelector);
        }

        @Bean PullTaskPullWavePlanningResources planningResources(
                PullTaskGroupExecutionMapper executionMapper,
                PullTaskPullWaveMapper waveMapper,
                PullTaskPullCallMapper callMapper,
                PullTaskPullCallMemberAttemptMapper attemptMapper,
                PullTaskPullWavePlanningSelection selection) {
            return new PullTaskPullWavePlanningResources(
                    executionMapper, waveMapper, callMapper, attemptMapper, selection);
        }

        @Bean PullTaskPullWavePlanningTransactionService planningService(
                PullTaskMapper taskMapper,
                PullTaskStandardSettingMapper settingMapper,
                PullTaskMaterialMemberMapper materialMapper,
                PullTaskGroupAccountMapper groupAccountMapper,
                PullTaskPullWavePlanningResources resources) {
            return new PullTaskPullWavePlanningTransactionService(
                    taskMapper, settingMapper, materialMapper, groupAccountMapper, resources);
        }
    }
}
