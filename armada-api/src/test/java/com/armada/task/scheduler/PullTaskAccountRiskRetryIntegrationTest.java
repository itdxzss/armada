package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import com.armada.task.service.impl.PullTaskPullCallResultCoordination;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 真实回调事务、Mapper、派发与波次收口共同覆盖账号受限后的原群局部补拉。 */
@SpringJUnitConfig(PullTaskAccountRiskRetryIntegrationTest.TestConfig.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class PullTaskAccountRiskRetryIntegrationTest {

    private static final long EXECUTION_ID = 501L;
    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executions;
    @Autowired private PullTaskPullCallMapper calls;
    @Autowired private PullTaskPullCallMemberAttemptMapper attempts;
    @Autowired private PullTaskMaterialMemberMapper materials;
    @Autowired private PullTaskGroupAccountMapper accounts;
    @Autowired private PullTaskPullWaveMapper waves;
    @Autowired private PullTaskPullExecutionProcessor processor;
    @Autowired private PullTaskPullCallParticipantResultService results;
    @Autowired private AccountOperationRestrictionService restrictions;
    @Autowired private AccountProtocolLookupService lookup;
    @Autowired private ProtocolCommandOutboxService outbox;
    private JdbcTemplate jdbc;
    private final Set<Long> restricted = new HashSet<>();

    @BeforeEach
    void setUp() throws SQLException {
        reset(lookup, outbox, restrictions);
        restricted.clear();
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO pull_task
                (id, tenant_id, task_type, task_name, mode, status, config_json, created_at, updated_at)
                VALUES (100, 7, 'STANDARD', 'risk-retry', 'NORMAL_LINK', 'EXECUTING', '{}', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_standard_setting
                (tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, pull_count_max,
                 pull_interval_seconds, puller_count_per_group, station_count_per_call, concurrent_group_count,
                 puller_risk_minutes, required_manager_count, manager_group_id, puller_group_id,
                 station_group_id, manager_group_name, puller_group_name, station_group_name, created_at, updated_at)
                VALUES (7, 100, 1, 1, 4, 4, 10, 2, 0, 1, 5, 1, 88, 89, 90, 'm', 'p', 's', 1, 1)
                """);
        jdbc.update("""
                INSERT INTO pull_task_group_execution
                (id, tenant_id, task_id, seq, group_link_id, group_jid, source_file_index, source_file_name,
                 valid_member_count, execution_status, stage, version, created_at, updated_at)
                VALUES (501, 7, 100, 1, 9001, '120363risk@g.us', 1, 'risk.txt', 4, 2, 6, 1, 1, 1)
                """);
        for (int seq = 1; seq <= 4; seq++) {
            jdbc.update("""
                    INSERT INTO pull_task_material_member
                    (id, tenant_id, group_execution_id, member_seq, source_line_no, normalized_phone,
                     admin_required, created_at, updated_at) VALUES (?, 7, 501, ?, ?, ?, 0, 1, 1)
                    """, 600L + seq, seq, seq, "861390000060" + seq);
        }
        for (int seq = 1; seq <= 2; seq++) {
            jdbc.update("""
                    INSERT INTO pull_task_group_account
                    (id, tenant_id, task_id, group_execution_id, account_id, account_phone, role_type,
                     role_seq, membership_status, availability_status, occupied_at, created_at, updated_at)
                    VALUES (?, 7, 100, 501, ?, ?, 2, ?, 2, 1, 1, 1, 1)
                    """, 900L + seq, 1000L + seq, "861380000100" + seq, seq);
        }
        when(lookup.findEligiblePullerProtocolRefs(anyList())).thenAnswer(invocation -> {
            List<Long> requested = invocation.getArgument(0);
            return requested.stream().filter(id -> !restricted.contains(id))
                    .map(id -> new ProtocolAccountRef(id, ProtocolBackend.WEB,
                            "puller-" + id, "861380000" + id)).toList();
        });
        when(restrictions.restrictPulling(anyLong(), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> restricted.add(invocation.getArgument(0)));
        AtomicInteger sequence = new AtomicInteger();
        when(outbox.enqueuePullTaskBatchAddCommands(anyList())).thenAnswer(invocation ->
                new ProtocolCommandOutboxEnqueueResult("pull-task:100",
                        List.of("cmd-risk-" + sequence.incrementAndGet()), 1));
        assertThat(AopUtils.isAopProxy(results)).isTrue();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RATE_LIMITED", "ACCOUNT_REACHOUT_RESTRICTED", " rate_limited "})
    void riskRequeuesOnlyUnsuccessfulMembersAndFinishesAfterHealthyPullerReturns(String reason) {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, reason);
        assertThat(restricted).containsExactly(first.getPullerAccountId());
        assertThat(runAt(5_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskPullCall retry = retryCall();
        assertRetryPlan(retry, List.of(603L, 604L));

        // 重复受限回执不会重置已规划的后继，也不会多建波次。
        report(first, 603L, "UNKNOWN", reason, 6_000L);
        assertThat(calls.selectByExecution(EXECUTION_ID)).hasSize(2);
        assertRetryPlan(retry, List.of(603L, 604L));
        TenantContext.set(8L);
        assertThat(attempts.selectRetryCandidatesByWave(first.getPullWaveId(), 4)).isEmpty();
        TenantContext.set(7L);

        assertThat(runAt(65_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        retry = retryCall();
        assertThat(retry.getPullerAccountId()).isEqualTo(1002L);
        assertThat(retry.getCommandId()).isNotEqualTo(first.getCommandId());
        report(retry, 603L, "SUCCESS", "OK", 66_000L);
        report(retry, 604L, "SUCCESS", "OK", 66_000L);
        assertThat(runAt(70_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(executions.selectById(EXECUTION_ID).getStage())
                .isEqualTo(PullTaskExecutionStage.CLOSING.code());
        assertThat(materials.selectByExecution(EXECUTION_ID))
                .extracting(member -> member.getPullStatus()).containsExactly(2, 3, 2, 2);
        report(first, 603L, "FAILED", "PRIVACY_BLOCKED", 71_000L);
        assertThat(materials.selectByExecution(EXECUTION_ID).get(2).getPullStatus())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
    }

    @Test
    void lateSuccessPrunesRiskRetryAndDuplicateCallbackDoesNotRestoreIt() {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, "RATE_LIMITED");
        runAt(5_000L);
        report(first, 603L, "SUCCESS", "OK", 6_000L);
        report(first, 603L, "SUCCESS", "OK", 6_000L);
        PullTaskPullCall retry = retryCall();
        assertThat(retry.getPlannedMaterialCount()).isEqualTo(1);
        assertThat(attempts.selectByCall(retry.getId()))
                .filteredOn(a -> a.getLifecycleStatus() == PullTaskParticipantAttemptStatus.PLANNED.code())
                .extracting(PullTaskPullCallMemberAttempt::getParticipantRefId).containsExactly(604L);
        runAt(65_000L);
        assertThat(retryCall().getPlannedMaterialCount()).isEqualTo(1);
    }

    @Test
    void noHealthyReplacementWaitsForResourceInsteadOfClosingOrSending() {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, "RATE_LIMITED");
        runAt(5_000L);
        restricted.add(1002L);
        runAt(65_000L);
        assertThat(executions.selectById(EXECUTION_ID).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(retryCall().getCallStatus()).isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(retryCall().getCommandId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "ACCOUNT_NOT_ONLINE", "NEED_REAUTH", "PROTOCOL_RESULT_UNCONFIRMED",
            "ACCOUNT_BANNED", "UNKNOWN_FUTURE_REASON"})
    void unrelatedUncertainResultDoesNotCreateRetry(String reason) {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, reason);
        assertThat(runAt(5_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(calls.selectByExecution(EXECUTION_ID)).hasSize(1);
        assertThat(materials.selectByExecution(EXECUTION_ID))
                .extracting(member -> member.getPullStatus()).containsExactly(2, 3, 4, 4);
    }

    @Test
    void exhaustedAccountRiskBudgetKeepsUnknownAndClosesWithoutFifthAttempt() {
        PullTaskPullCall first = submitFirstCall();
        jdbc.update("UPDATE pull_task_pull_call_member_attempt SET attempt_no=4 WHERE pull_call_id=?", first.getId());
        mixedResults(first, "RATE_LIMITED");
        assertThat(runAt(5_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(calls.selectByExecution(EXECUTION_ID)).hasSize(1);
        assertThat(materials.selectByExecution(EXECUTION_ID))
                .extracting(member -> member.getPullStatus()).containsExactly(2, 3, 4, 4);
        assertThat(materials.selectByExecution(EXECUTION_ID).get(2).getPullFailureCount()).isZero();
    }

    @Test
    void successiveRiskWavesUseBackoffAndStopAfterFourActualCalls() {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, "RATE_LIMITED");
        long[] collectedAt = {5_000L, 70_000L, 195_000L};
        long[] dispatchedAt = {65_000L, 190_000L, 435_000L};
        for (int retryIndex = 0; retryIndex < 3; retryIndex++) {
            assertThat(runAt(collectedAt[retryIndex])).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
            PullTaskPullCall retry = calls.selectByExecution(EXECUTION_ID).get(retryIndex + 1);
            assertThat(waves.selectById(retry.getPullWaveId()).getNextDispatchAt())
                    .isEqualTo(dispatchedAt[retryIndex]);
            // 外部账号域已冷却恢复，允许后续波次继续选择健康账号。
            restricted.clear();
            runAt(dispatchedAt[retryIndex]);
            retry = calls.selectByExecution(EXECUTION_ID).get(retryIndex + 1);
            assertThat(attempts.selectByCall(retry.getId()))
                    .extracting(PullTaskPullCallMemberAttempt::getAttemptNo).containsOnly(retryIndex + 2);
            report(retry, 603L, "UNKNOWN", "RATE_LIMITED", dispatchedAt[retryIndex] + 1_000L);
            report(retry, 604L, "UNKNOWN", "RATE_LIMITED", dispatchedAt[retryIndex] + 1_000L);
        }
        assertThat(runAt(440_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(calls.selectByExecution(EXECUTION_ID)).hasSize(4);
        assertThat(materials.selectByExecution(EXECUTION_ID))
                .extracting(member -> member.getPullStatus()).containsExactly(2, 3, 4, 4);
        assertThat(materials.selectByExecution(EXECUTION_ID).get(2).getPullFailureCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RATE_LIMITED", "ACCOUNT_REACHOUT_RESTRICTED", " account_reachout_restricted "})
    void stationRiskSurvivesNormalizationAndRetriesWithoutReaddingSuccessfulMaterials(String reason) {
        jdbc.update("UPDATE pull_task_standard_setting SET station_count_per_call=1 WHERE task_id=100");
        when(lookup.findOnlinePullTaskAccountsByGroupId(90L)).thenReturn(List.of(
                new ProtocolAccountRef(2001L, ProtocolBackend.WEB, "station-2001", "8613800002001")));
        PullTaskPullCall first = submitFirstCall();
        assertThat(first.getPlannedStationCount()).isEqualTo(1);
        long stationId = attempts.selectByCall(first.getId()).stream()
                .filter(a -> a.getParticipantType() == 2).findFirst().orElseThrow().getParticipantRefId();
        for (long id = 601L; id <= 604L; id++) {
            report(first, id, "SUCCESS", "OK", 2_000L);
        }
        report(first, stationId, "UNKNOWN", reason, 2_000L);
        assertThat(runAt(5_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskPullCall retry = retryCall();
        assertThat(retry.getPlannedMaterialCount()).isZero();
        assertThat(retry.getPlannedStationCount()).isEqualTo(1);
        assertRetryPlan(retry, List.of(stationId));
        assertThat(accounts.selectById(stationId).getMembershipFailureCount()).isZero();
        runAt(65_000L);
        retry = retryCall();
        assertThat(retry.getPullerAccountId()).isEqualTo(1002L);
        report(retry, stationId, "SUCCESS", "OK", 66_000L);
        assertThat(runAt(70_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(accounts.selectById(stationId).getMembershipStatus()).isEqualTo(2);
    }

    @Test
    void historicalClosedAccountRiskIsNotReopenedByNewCandidatePolicy() {
        PullTaskPullCall first = submitFirstCall();
        mixedResults(first, "RATE_LIMITED");
        jdbc.update("""
                UPDATE pull_task_pull_call_member_attempt SET lifecycle_status=3, released_at=NULL
                WHERE pull_call_id=? AND protocol_outcome='UNKNOWN'
                """, first.getId());
        jdbc.update("""
                UPDATE pull_task_material_member SET pull_status=4, pull_call_id=?
                WHERE group_execution_id=501 AND id IN (603,604)
                """, first.getId());
        assertThat(runAt(5_000L)).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(calls.selectByExecution(EXECUTION_ID)).hasSize(1);
    }

    private PullTaskPullCall submitFirstCall() {
        assertThat(runAt(1_000L)).isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        PullTaskPullCall first = calls.selectByExecution(EXECUTION_ID).get(0);
        assertThat(first.getPullerAccountId()).isEqualTo(1001L);
        assertThat(first.getPlannedMaterialCount()).isEqualTo(4);
        return first;
    }

    private void mixedResults(PullTaskPullCall call, String riskReason) {
        report(call, 601L, "SUCCESS", "OK", 2_000L);
        report(call, 602L, "FAILED", "PRIVACY_BLOCKED", 2_000L);
        report(call, 603L, "UNKNOWN", riskReason, 2_000L);
        report(call, 604L, "UNKNOWN", riskReason, 2_000L);
    }

    private void report(PullTaskPullCall call, long materialId, String outcome, String reason, long now) {
        PullTaskPullCallMemberAttempt attempt = attempts.selectByCall(call.getId()).stream()
                .filter(a -> a.getParticipantRefId() == materialId).findFirst().orElseThrow();
        assertThat(results.handle(new PullTaskBatchParticipantCallback(
                7L, 100L, EXECUTION_ID, call.getId(), call.getPullerAccountId(),
                // 每个新命令的协议投递序号为 1，与参与者跨波次的逻辑 attempt_no 无关。
                "puller-" + call.getPullerAccountId(), call.getCommandId(), 1,
                attempt.getTargetJid(), PullTaskBatchParticipantProtocolOutcome.valueOf(outcome),
                "UNKNOWN".equals(outcome) ? PullTaskParticipantExecutionState.UNCERTAIN
                        : PullTaskParticipantExecutionState.STARTED,
                reason, reason, true, now))).isTrue();
    }

    private PullTaskExecutionDispatchResult runAt(long now) {
        jdbc.update("UPDATE pull_task_group_execution SET lock_owner='worker-risk', lock_expires_at=? WHERE id=501", now + 5_000L);
        return processor.process(executions.selectById(EXECUTION_ID), "worker-risk", now);
    }

    private PullTaskPullCall retryCall() {
        List<PullTaskPullCall> all = calls.selectByExecution(EXECUTION_ID);
        assertThat(all).hasSize(2);
        return all.get(1);
    }

    private void assertRetryPlan(PullTaskPullCall retry, List<Long> ids) {
        assertThat(executions.selectById(EXECUTION_ID).getStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(executions.selectById(EXECUTION_ID).getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(waves.selectById(retry.getPullWaveId()).getNextDispatchAt()).isEqualTo(65_000L);
        assertThat(attempts.selectByCall(retry.getId()))
                .extracting(PullTaskPullCallMemberAttempt::getParticipantRefId).containsExactlyElementsOf(ids);
        assertThat(attempts.selectByCall(retry.getId()))
                .extracting(PullTaskPullCallMemberAttempt::getAttemptNo).containsOnly(2);
        assertThat(attempts.selectByCall(retry.getId()))
                .extracting(PullTaskPullCallMemberAttempt::getFailureCountBefore).containsOnly(0L);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PullTaskPullWaveDispatchIntegrationTest.TestConfig.class)
    static class TestConfig {
        @Bean AccountOperationRestrictionService restrictions() {
            return mock(AccountOperationRestrictionService.class);
        }

        @Bean
        @Primary
        PullTaskPullCallParticipantResultService realResults(
                PullTaskGroupExecutionMapper executions, PullTaskPullCallMapper calls,
                PullTaskPullCallMemberAttemptMapper attempts, PullTaskMaterialMemberMapper materials,
                PullTaskGroupAccountMapper accounts, AccountOperationRestrictionService restrictions,
                PullTaskStickyPullerTransactionService sticky, PullTaskPullWaveProgressService progress,
                ApplicationEventPublisher publisher) {
            return new PullTaskPullCallParticipantResultService(
                    new PullTaskUnknownResultResources(mock(PullTaskAccountActionMapper.class),
                            calls, attempts, materials, accounts), executions, restrictions,
                    new PullTaskPullCallResultCoordination(sticky,
                            mock(PullTaskGroupExecutionFailureService.class), progress,
                            mock(GroupDataPackageTaskProjectionService.class)), publisher);
        }
    }
}
