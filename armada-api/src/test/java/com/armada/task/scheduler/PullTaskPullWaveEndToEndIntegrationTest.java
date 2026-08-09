package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskPullWaveType;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 五个非阻塞初始调用、拉手切换和六号码统一重试的纵向验收。 */
@SpringJUnitConfig(PullTaskPullWaveDispatchIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskPullWaveEndToEndIntegrationTest {

    private static final long TASK_ID = 100L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper accountMapper;
    @Autowired private PullTaskMaterialMemberMapper materialMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskStickyPullerTransactionService stickyPullers;
    @Autowired private PullTaskPullExecutionProcessor processor;

    private long executionId;
    private long firstPullerRoleId;
    private long secondPullerRoleId;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
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
        materialMapper.batchInsert(materials(27));
        firstPullerRoleId = insertPuller(902L, 1);
        secondPullerRoleId = insertPuller(904L, 2);
        when(accountLookup.findActiveProtocolRefs(anyList())).thenReturn(List.of(
                protocol(902L), protocol(904L)));
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
    void fiveCallsDispatchByTimeThenOneRetryWaveContainsExactlySixEligibleNumbers()
            throws SQLException {
        dispatchAt(1_000L);
        dispatchAt(11_000L);
        invalidateFirstPullerAtRateLimit();
        dispatchAt(21_000L);
        dispatchAt(31_000L);
        dispatchAt(41_000L);

        TenantContext.set(7L);
        List<PullTaskPullCall> initialCalls = callMapper.selectByExecution(executionId);
        assertThat(initialCalls).extracting(PullTaskPullCall::getPlannedMaterialCount)
                .containsExactly(6, 6, 6, 6, 3);
        assertThat(initialCalls).extracting(PullTaskPullCall::getSubmittedAt)
                .containsExactly(1_000L, 11_000L, 21_000L, 31_000L, 41_000L);
        assertThat(initialCalls.subList(0, 2))
                .extracting(PullTaskPullCall::getPullerGroupAccountId)
                .containsOnly(firstPullerRoleId);
        assertThat(initialCalls.subList(2, 5))
                .extracting(PullTaskPullCall::getPullerGroupAccountId)
                .containsOnly(secondPullerRoleId);
        assertThat(initialCalls).extracting(PullTaskPullCall::getPullerAssignmentSeq)
                .containsExactly(1L, 1L, 2L, 2L, 2L);

        PullTaskPullWave initialWave = waveMapper.selectActiveByExecution(
                executionId, activeWaveStatuses());
        List<PullTaskPullCallMemberAttempt> attempts = initialCalls.stream()
                .flatMap(call -> attemptMapper.selectByCall(call.getId()).stream())
                .sorted(Comparator.comparing(PullTaskPullCallMemberAttempt::getId))
                .toList();
        OutcomeGroups outcomes = writeAcceptanceFacts(initialCalls, attempts);

        PullTaskGroupExecution settlementCandidate = claim("worker-1", 100_000L, 110_000L);
        assertThat(processor.process(settlementCandidate, "worker-1", 100_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        TenantContext.set(7L);
        assertThat(waveMapper.selectById(initialWave.getId()).getWaveStatus())
                .isEqualTo(PullTaskPullWaveStatus.SETTLED.code());
        PullTaskGroupExecution afterSettlement = executionMapper.selectById(executionId);
        PullTaskPullWave retryWave = waveMapper.selectById(afterSettlement.getActivePullWaveId());
        assertThat(retryWave.getWaveType()).isEqualTo(PullTaskPullWaveType.RETRY.code());
        List<PullTaskPullCall> allCalls = callMapper.selectByExecution(executionId);
        PullTaskPullCall retryCall = allCalls.stream()
                .filter(call -> retryWave.getId().equals(call.getPullWaveId()))
                .findFirst().orElseThrow();
        assertThat(attemptMapper.selectByCall(retryCall.getId()))
                .extracting(PullTaskPullCallMemberAttempt::getParticipantRefId)
                .containsExactlyElementsOf(outcomes.retryableParticipantIds());
        assertThat(outcomes.retryableParticipantIds()).hasSize(6);
        assertThat(outcomes.finalUnknownParticipantId())
                .isNotIn(outcomes.retryableParticipantIds());

        dispatchAt(100_001L);

        TenantContext.set(7L);
        PullTaskPullCall submittedRetry = callMapper.selectByExecution(executionId).stream()
                .filter(call -> retryWave.getId().equals(call.getPullWaveId()))
                .findFirst().orElseThrow();
        assertThat(submittedRetry.getCallStatus())
                .isEqualTo(PullTaskPullCallStatus.SUBMITTED.code());
        assertThat(submittedRetry.getPullerGroupAccountId())
                .isEqualTo(secondPullerRoleId);
        assertThat(submittedRetry.getPullerAssignmentSeq()).isEqualTo(2L);
    }

    private void dispatchAt(long now) {
        PullTaskGroupExecution candidate = claim("worker-1", now, now + 5_000L);
        assertThat(processor.process(candidate, "worker-1", now))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private void invalidateFirstPullerAtRateLimit() throws SQLException {
        TenantContext.set(7L);
        List<PullTaskPullCall> calls = callMapper.selectByExecution(executionId);
        assertThat(stickyPullers.invalidateIfCurrent(
                executionMapper.selectById(executionId), calls.get(1),
                "RATE_LIMITED", 12_000L)).isTrue();
        execute("UPDATE pull_task_group_account SET availability_status="
                + PullTaskGroupAccountAvailability.RISK_COOLDOWN.code()
                + ", unavailable_reason_code='RATE_LIMITED' WHERE id="
                + firstPullerRoleId);
    }

    private OutcomeGroups writeAcceptanceFacts(
            List<PullTaskPullCall> calls,
            List<PullTaskPullCallMemberAttempt> attempts) throws SQLException {
        List<Long> retryable = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            PullTaskPullCallMemberAttempt attempt = attempts.get(index);
            if (index < 20) {
                writeAttempt(attempt, PullTaskParticipantAttemptStatus.CLOSED.code(),
                        "SUCCESS", "STARTED", null,
                        PullTaskMaterialPullStatus.SUCCESS.code(), 0L, false);
            } else if (index < 23) {
                writeAttempt(attempt, PullTaskParticipantAttemptStatus.CLOSED.code(),
                        "FAILED", "STARTED", "PRIVACY",
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 1L, true);
                retryable.add(attempt.getParticipantRefId());
            } else if (index < 25) {
                writeAttempt(attempt, PullTaskParticipantAttemptStatus.RELEASED.code(),
                        "UNKNOWN", "NOT_STARTED", "ACCOUNT_NOT_ONLINE",
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, true);
                retryable.add(attempt.getParticipantRefId());
            } else if (index == 25) {
                writeAttempt(attempt, PullTaskParticipantAttemptStatus.RELEASED.code(),
                        "UNKNOWN", "UNCERTAIN", "ROSTER_NOT_PRESENT",
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, true);
                retryable.add(attempt.getParticipantRefId());
            } else {
                writeAttempt(attempt, PullTaskParticipantAttemptStatus.CLOSED.code(),
                        "UNKNOWN", "UNCERTAIN", "ROSTER_QUERY_UNAVAILABLE",
                        PullTaskMaterialPullStatus.UNKNOWN.code(), 0L, false);
            }
        }
        for (PullTaskPullCall call : calls) {
            execute("UPDATE pull_task_pull_call SET call_status="
                    + PullTaskPullCallStatus.WRITTEN_BACK.code()
                    + ", result_at=90000, updated_at=90000 WHERE id=" + call.getId());
        }
        return new OutcomeGroups(List.copyOf(retryable),
                attempts.get(attempts.size() - 1).getParticipantRefId());
    }

    private void writeAttempt(
            PullTaskPullCallMemberAttempt attempt,
            int lifecycle,
            String outcome,
            String executionState,
            String reasonCode,
            int materialStatus,
            long failureCount,
            boolean clearCall) throws SQLException {
        String reason = reasonCode == null ? "NULL" : "'" + reasonCode + "'";
        execute("UPDATE pull_task_pull_call_member_attempt SET lifecycle_status=" + lifecycle
                + ", active_slot=NULL, protocol_outcome='" + outcome
                + "', execution_state='" + executionState + "', reason_code=" + reason
                + ", result_at=90000, released_at="
                + (lifecycle == PullTaskParticipantAttemptStatus.RELEASED.code()
                ? "90000" : "NULL")
                + ", updated_at=90000 WHERE id=" + attempt.getId());
        execute("UPDATE pull_task_material_member SET pull_status=" + materialStatus
                + ", pull_failure_count=" + failureCount
                + ", active_pull_attempt_id=NULL, pull_call_id="
                + (clearCall ? "NULL" : attempt.getPullCallId())
                + " WHERE id=" + attempt.getParticipantRefId());
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
                + "VALUES (7, 100, 1, 1, 6, 6, 10, 2, 0, 1, 5, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
    }

    private long insertPuller(long accountId, int roleSeq) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(TASK_ID);
        row.setGroupExecutionId(executionId);
        row.setAccountId(accountId);
        row.setAccountPhone("8613800000" + accountId);
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(2);
        row.setOccupiedAt(100L);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        accountMapper.insert(row);
        accountMapper.updateMembership(
                row.getId(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 100L, 100L);
        return row.getId();
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
        row.setTotalLineCount(27);
        row.setValidMemberCount(27);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private static ProtocolAccountRef protocol(long accountId) {
        return new ProtocolAccountRef(
                accountId, ProtocolBackend.WEB,
                "puller-" + accountId, "8613800000" + accountId);
    }

    private static List<Integer> activeWaveStatuses() {
        return List.of(
                PullTaskPullWaveStatus.DISPATCHING.code(),
                PullTaskPullWaveStatus.COLLECTING.code());
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record OutcomeGroups(
            List<Long> retryableParticipantIds,
            long finalUnknownParticipantId) {
    }
}
