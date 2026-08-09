package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 使用真实 XML 验证升级前开放调用只挂接波次，不被重发或重新分区。 */
@SpringJUnitConfig(PullTaskPullWavePlanningIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWaveLegacyBootstrapIntegrationTest {

    private static final long EXECUTION_ID = 501L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private PullTaskPullWavePlanningTransactionService service;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        insertParentSettingAndExecution();
        insertLegacyFacts();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void attachesOnlyOpenLegacyCallsAndAppendsRemainingUnconsumedMaterial()
            throws SQLException {
        PullTaskPullWavePreparation result = service.prepare(
                claim("worker-1", 20_000L, 25_000L), "worker-1", 20_010L);

        TenantContext.set(7L);
        assertThat(result.ready()).isTrue();
        assertThat(result.wave().getWaveStatus())
                .isEqualTo(PullTaskPullWaveStatus.DISPATCHING.code());
        assertThat(result.wave().getPlannedCallCount()).isEqualTo(3);
        assertThat(result.wave().getNextCallSeq()).isEqualTo(2);
        assertThat(result.call().getId()).isEqualTo(402L);

        List<PullTaskPullCall> calls = callMapper.selectByExecution(EXECUTION_ID);
        assertThat(calls).extracting(PullTaskPullCall::getWaveCallSeq)
                .containsExactly(1, 2, null, 3);
        assertThat(calls.get(0).getCommandId()).isEqualTo("legacy-command");
        assertThat(calls.get(0).getIdempotencyKey()).isEqualTo("legacy-submitted-key");
        assertThat(calls.get(0).getSubmittedAt()).isEqualTo(100L);
        assertThat(calls.get(0).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());
        assertThat(calls.get(1).getIdempotencyKey()).isEqualTo("legacy-planned-key");
        assertThat(calls.get(2).getPullWaveId()).isNull();
        assertThat(calls.get(2).getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        assertThat(calls.get(3).getPlannedMaterialCount()).isEqualTo(1);

        assertThat(attemptMapper.selectByCall(401L)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getPullWaveId()).isEqualTo(result.wave().getId());
                    assertThat(row.getLifecycleStatus())
                            .isEqualTo(PullTaskParticipantAttemptStatus.SUBMITTED.code());
                });
        assertThat(attemptMapper.selectByCall(402L)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getPullWaveId()).isEqualTo(result.wave().getId());
                    assertThat(row.getParticipantRefId()).isEqualTo(602L);
                });
        assertThat(attemptMapper.selectByCall(403L)).singleElement()
                .satisfies(row -> assertThat(row.getPullWaveId()).isNull());
        assertThat(executionMapper.selectById(EXECUTION_ID)).satisfies(execution -> {
            assertThat(execution.getActivePullWaveId()).isEqualTo(result.wave().getId());
            assertThat(execution.getActivePullerGroupAccountId()).isEqualTo(901L);
            assertThat(execution.getPullerAssignmentSeq()).isEqualTo(1L);
        });
        assertThat(waveMapper.selectActiveByExecution(
                EXECUTION_ID, List.of(
                        PullTaskPullWaveStatus.DISPATCHING.code(),
                        PullTaskPullWaveStatus.COLLECTING.code())).getId())
                .isEqualTo(result.wave().getId());
    }

    @Test
    void recentLegacySubmissionPreservesIntervalBeforeFirstPlannedCall() {
        PullTaskPullWavePreparation result = service.prepare(
                claim("worker-1", 600L, 20_000L), "worker-1", 610L);

        TenantContext.set(7L);
        assertThat(result.ready()).isFalse();
        assertThat(result.result()).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskGroupExecution execution = executionMapper.selectById(EXECUTION_ID);
        assertThat(execution.getNextRunAt()).isEqualTo(10_100L);
        assertThat(execution.getLockOwner()).isNull();
        PullTaskPullCall planned = callMapper.selectByExecution(EXECUTION_ID).get(1);
        assertThat(planned.getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(planned.getCommandId()).isNull();
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

    private void insertParentSettingAndExecution() throws SQLException {
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
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, execution_status, "
                + "stage, group_jid, manual_paused, next_puller_index, puller_assignment_seq, "
                + "next_run_at, version, created_at, updated_at) VALUES ("
                + EXECUTION_ID + ", 7, 100, 1, 'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, "
                + "'material.txt', 2, 6, '120363group@g.us', 0, 0, 0, 0, 6, 100, 100)");
    }

    private void insertLegacyFacts() throws SQLException {
        execute("INSERT INTO pull_task_group_account "
                + "(id, tenant_id, task_id, group_execution_id, account_id, account_phone, "
                + "role_type, role_seq, membership_status, availability_status, created_at, updated_at) "
                + "VALUES (901, 7, 100, " + EXECUTION_ID
                + ", 902, '8613800000902', 2, 1, 2, 1, 100, 100)");
        execute("INSERT INTO pull_task_pull_call "
                + "(id, tenant_id, task_id, group_execution_id, call_seq, "
                + "puller_group_account_id, puller_account_id, planned_material_count, "
                + "planned_station_count, call_status, command_id, idempotency_key, "
                + "submitted_at, result_at, created_at, updated_at) VALUES "
                + "(401, 7, 100, 501, 1, 901, 902, 1, 0, 2, 'legacy-command', "
                + "'legacy-submitted-key', 100, NULL, 100, 100), "
                + "(402, 7, 100, 501, 2, 901, 902, 1, 0, 1, NULL, "
                + "'legacy-planned-key', NULL, NULL, 100, 100), "
                + "(403, 7, 100, 501, 3, 901, 902, 1, 0, 3, 'done-command', "
                + "'done-key', 50, 80, 50, 80)");
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, "
                + "normalized_phone, admin_required, pull_call_id, pull_status, "
                + "active_pull_attempt_id, admin_status, created_at, updated_at) VALUES "
                + "(601, 7, 501, 1, 1, '8613900000001', 0, 401, 1, 701, 0, 100, 100), "
                + "(602, 7, 501, 2, 2, '8613900000002', 0, 402, 1, 702, 0, 100, 100), "
                + "(603, 7, 501, 3, 3, '8613900000003', 0, 403, 2, NULL, 0, 100, 100), "
                + "(604, 7, 501, 4, 4, '8613900000004', 0, NULL, 0, NULL, 0, 100, 100)");
        execute("INSERT INTO pull_task_pull_call_member_attempt "
                + "(id, tenant_id, task_id, group_execution_id, pull_call_id, participant_type, "
                + "participant_ref_id, target_phone, target_jid, puller_group_account_id, "
                + "attempt_no, failure_count_before, lifecycle_status, active_slot, "
                + "protocol_outcome, execution_state, submitted_at, result_at, created_at, updated_at) "
                + "VALUES (701, 7, 100, 501, 401, 1, 601, '8613900000001', "
                + "'8613900000001@s.whatsapp.net', 901, 1, 0, 2, 1, NULL, NULL, 100, NULL, 100, 100), "
                + "(702, 7, 100, 501, 402, 1, 602, '8613900000002', "
                + "'8613900000002@s.whatsapp.net', 901, 1, 0, 1, 1, NULL, NULL, NULL, NULL, 100, 100), "
                + "(703, 7, 100, 501, 403, 1, 603, '8613900000003', "
                + "'8613900000003@s.whatsapp.net', 901, 1, 0, 3, NULL, 'SUCCESS', 'STARTED', "
                + "50, 80, 50, 80)");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
