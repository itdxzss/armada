package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
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
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskPullWaveType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
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

/** 使用真实 Mapper XML 验证收集轮询、一次结算和重试波原子替换。 */
@SpringJUnitConfig(PullTaskPullWaveSettlementIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWaveSettlementIntegrationTest {

    private static final long EXECUTION_ID = 501L;
    private static final long MATERIAL_ID = 601L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private PullTaskPullWaveSettlementTransactionService service;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        insertParentAndSetting();
        insertExecution();
        insertMaterial();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void openAttemptDefersToNextReconciliationScanWithoutResultProtection() throws SQLException {
        WaveFixture fixture = insertCollectingWave();
        attemptMapper.markSubmittedByCall(fixture.call().getId(), 1_000L);
        callMapper.markSubmitted(fixture.call().getId(), "cmd-open", 1_000L);

        assertThat(service.settle(
                executionMapper.selectById(EXECUTION_ID), fixture.wave(),
                "worker-1", 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        PullTaskGroupExecution saved = executionMapper.selectById(EXECUTION_ID);
        assertThat(saved.getNextRunAt()).isEqualTo(3_000L);
        assertThat(saved.getLockOwner()).isNull();
        assertThat(waveMapper.selectById(fixture.wave().getId()).getWaveStatus())
                .isEqualTo(PullTaskPullWaveStatus.COLLECTING.code());
    }

    @Test
    void fullyClosedWaveSettlesOnceAndAdvancesToClosing() throws SQLException {
        WaveFixture fixture = insertCollectingWave();
        closeAttempt(fixture.attempt(), "SUCCESS");
        execute("UPDATE pull_task_material_member SET pull_status="
                + PullTaskMaterialPullStatus.SUCCESS.code()
                + ", pull_call_id=NULL, active_pull_attempt_id=NULL WHERE id=" + MATERIAL_ID);

        assertThat(service.settle(
                executionMapper.selectById(EXECUTION_ID), fixture.wave(),
                "worker-1", 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        PullTaskGroupExecution saved = executionMapper.selectById(EXECUTION_ID);
        assertThat(saved.getActivePullWaveId()).isNull();
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.CLOSING.code());
        assertThat(saved.getLockOwner()).isNull();
        assertThat(waveMapper.selectById(fixture.wave().getId()))
                .satisfies(wave -> {
                    assertThat(wave.getWaveStatus())
                            .isEqualTo(PullTaskPullWaveStatus.SETTLED.code());
                    assertThat(wave.getSettledAt()).isEqualTo(2_000L);
                });
    }

    @Test
    void explicitFailureCreatesOneRetryWaveAndPreservesStickyAssignment() throws SQLException {
        WaveFixture fixture = insertCollectingWave();
        attemptMapper.markSubmittedByCall(fixture.call().getId(), 1_000L);
        callMapper.markSubmitted(fixture.call().getId(), "cmd-failed", 1_000L);
        closeAttempt(fixture.attempt(), "FAILED");
        execute("UPDATE pull_task_material_member SET pull_status=0, pull_failure_count=1, "
                + "pull_call_id=NULL, active_pull_attempt_id=NULL WHERE id=" + MATERIAL_ID);

        assertThat(service.settle(
                executionMapper.selectById(EXECUTION_ID), fixture.wave(),
                "worker-1", 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        PullTaskGroupExecution saved = executionMapper.selectById(EXECUTION_ID);
        assertThat(saved.getActivePullWaveId()).isNotEqualTo(fixture.wave().getId());
        assertThat(saved.getActivePullerGroupAccountId()).isEqualTo(901L);
        assertThat(saved.getPullerAssignmentSeq()).isEqualTo(3L);
        PullTaskPullWave retry = waveMapper.selectById(saved.getActivePullWaveId());
        assertThat(retry.getWaveNo()).isEqualTo(2);
        assertThat(retry.getWaveType()).isEqualTo(PullTaskPullWaveType.RETRY.code());
        assertThat(retry.getWaveStatus()).isEqualTo(PullTaskPullWaveStatus.DISPATCHING.code());
        assertThat(retry.getNextDispatchAt()).isEqualTo(11_000L);
        assertThat(saved.getNextRunAt()).isEqualTo(11_000L);
        assertThat(callMapper.selectByExecution(EXECUTION_ID))
                .filteredOn(call -> retry.getId().equals(call.getPullWaveId()))
                .singleElement()
                .satisfies(call -> assertThat(attemptMapper.selectByCall(call.getId()))
                        .singleElement()
                        .extracting(PullTaskPullCallMemberAttempt::getParticipantRefId)
                        .isEqualTo(MATERIAL_ID));
    }

    @Test
    void unavailableRosterResultIsFinalUnknownAndCreatesNoRetryWave() throws SQLException {
        WaveFixture fixture = insertCollectingWave();
        execute("UPDATE pull_task_pull_call_member_attempt SET lifecycle_status="
                + PullTaskParticipantAttemptStatus.CLOSED.code()
                + ", active_slot=NULL, protocol_outcome='UNKNOWN', "
                + "execution_state='UNCERTAIN', reason_code='ROSTER_QUERY_UNAVAILABLE', "
                + "result_at=1500, updated_at=1500 WHERE id=" + fixture.attempt().getId());
        execute("UPDATE pull_task_pull_call SET call_status="
                + PullTaskPullCallStatus.WRITTEN_BACK.code()
                + ", result_at=1500, updated_at=1500 WHERE id=" + fixture.call().getId());
        execute("UPDATE pull_task_material_member SET pull_status="
                + PullTaskMaterialPullStatus.UNKNOWN.code()
                + ", pull_call_id=" + fixture.call().getId()
                + ", active_pull_attempt_id=NULL WHERE id=" + MATERIAL_ID);

        assertThat(attemptMapper.selectRetryCandidatesByWave(
                fixture.wave().getId(), 4L)).isEmpty();
        assertThat(service.settle(
                executionMapper.selectById(EXECUTION_ID), fixture.wave(),
                "worker-1", 2_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        PullTaskGroupExecution saved = executionMapper.selectById(EXECUTION_ID);
        assertThat(saved.getActivePullWaveId()).isNull();
        assertThat(saved.getStage()).isEqualTo(PullTaskExecutionStage.CLOSING.code());
        assertThat(waveMapper.selectById(fixture.wave().getId()).getWaveStatus())
                .isEqualTo(PullTaskPullWaveStatus.SETTLED.code());
    }

    private WaveFixture insertCollectingWave() throws SQLException {
        PullTaskPullWave wave = new PullTaskPullWave();
        wave.setTaskId(100L);
        wave.setGroupExecutionId(EXECUTION_ID);
        wave.setWaveNo(1);
        wave.setWaveType(PullTaskPullWaveType.INITIAL.code());
        wave.setWaveStatus(PullTaskPullWaveStatus.COLLECTING.code());
        wave.setPlannedCallCount(1);
        wave.setNextDispatchAt(1_000L);
        wave.setDispatchCompletedAt(1_000L);
        wave.setCreatedAt(100L);
        wave.setUpdatedAt(100L);
        waveMapper.insertInitialized(wave);
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id=" + wave.getId()
                + " WHERE id=" + EXECUTION_ID);

        PullTaskPullCall call = new PullTaskPullCall();
        call.setTaskId(100L);
        call.setGroupExecutionId(EXECUTION_ID);
        call.setPullWaveId(wave.getId());
        call.setCallSeq(1);
        call.setWaveCallSeq(1);
        call.setPullerGroupAccountId(901L);
        call.setPullerAccountId(902L);
        call.setPullerAssignmentSeq(3L);
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(0);
        call.setIdempotencyKey("settlement-call-1");
        call.setCreatedAt(100L);
        call.setUpdatedAt(100L);
        callMapper.insertPlanned(call);

        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setTaskId(100L);
        attempt.setGroupExecutionId(EXECUTION_ID);
        attempt.setPullCallId(call.getId());
        attempt.setPullWaveId(wave.getId());
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(MATERIAL_ID);
        attempt.setTargetPhone("8613900000001");
        attempt.setTargetJid("8613900000001@s.whatsapp.net");
        attempt.setPullerGroupAccountId(901L);
        attempt.setPullerAssignmentSeq(3L);
        attempt.setAttemptNo(1);
        attempt.setFailureCountBefore(0L);
        attempt.setCreatedAt(100L);
        attempt.setUpdatedAt(100L);
        attemptMapper.insertPlanned(attempt);
        return new WaveFixture(wave, call, attempt);
    }

    private void closeAttempt(PullTaskPullCallMemberAttempt attempt, String outcome)
            throws SQLException {
        closeAttempt(attempt, outcome, "TIMEOUT");
    }

    /** 明确失败只有可重试原因码才会产生下一波次，因此原因码必须显式给出。 */
    private void closeAttempt(
            PullTaskPullCallMemberAttempt attempt, String outcome, String reasonCode)
            throws SQLException {
        execute("UPDATE pull_task_pull_call_member_attempt SET lifecycle_status="
                + PullTaskParticipantAttemptStatus.CLOSED.code()
                + ", active_slot=NULL, protocol_outcome='" + outcome
                + "', execution_state='STARTED', reason_code='" + reasonCode
                + "', result_at=1500, updated_at=1500 WHERE id="
                + attempt.getId());
        execute("UPDATE pull_task_pull_call SET call_status="
                + PullTaskPullCallStatus.WRITTEN_BACK.code()
                + ", result_at=1500, updated_at=1500 WHERE id=" + attempt.getPullCallId());
    }

    private void insertExecution() throws SQLException {
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, execution_status, "
                + "stage, active_puller_group_account_id, puller_assignment_seq, "
                + "lock_owner, lock_expires_at, version, created_at, updated_at) VALUES ("
                + EXECUTION_ID + ", 7, 100, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, "
                + "'material.txt', " + PullTaskExecutionStatus.EXECUTING.code() + ", "
                + PullTaskExecutionStage.PULL_EXECUTION.code()
                + ", 901, 3, 'worker-1', 10000, 6, 100, 100)");
    }

    private void insertMaterial() throws SQLException {
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, "
                + "normalized_phone, admin_required, pull_status, admin_status, "
                + "created_at, updated_at) VALUES ("
                + MATERIAL_ID + ", 7, " + EXECUTION_ID
                + ", 1, 1, '8613900000001', 0, 0, 0, 100, 100)");
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

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record WaveFixture(
            PullTaskPullWave wave,
            PullTaskPullCall call,
            PullTaskPullCallMemberAttempt attempt) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_wave_settlement_test");
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

        @Bean PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean PullTaskStationSelectionService stations(
                PullTaskGroupAccountMapper accounts) {
            return new PullTaskStationSelectionService(
                    accounts, mock(AccountProtocolLookupService.class));
        }

        @Bean PullTaskPullWavePlanningSelection selection(
                PullTaskStationSelectionService stations,
                PullTaskBatchSizeSelector batches) {
            return new PullTaskPullWavePlanningSelection(stations, batches);
        }

        @Bean PullTaskPullWavePlanningResources planningResources(
                PullTaskGroupExecutionMapper executions,
                PullTaskPullWaveMapper waves,
                PullTaskPullCallMapper calls,
                PullTaskPullCallMemberAttemptMapper attempts,
                PullTaskPullWavePlanningSelection selection) {
            return new PullTaskPullWavePlanningResources(
                    executions, waves, calls, attempts, selection);
        }

        @Bean PullTaskPullWavePlanningTransactionService planning(
                PullTaskMapper tasks,
                PullTaskStandardSettingMapper settings,
                PullTaskMaterialMemberMapper materials,
                PullTaskGroupAccountMapper accounts,
                PullTaskPullWavePlanningResources resources) {
            return new PullTaskPullWavePlanningTransactionService(
                    tasks, settings, materials, accounts, resources);
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
                    resources, planning, properties);
        }

        @Bean PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }
    }
}
