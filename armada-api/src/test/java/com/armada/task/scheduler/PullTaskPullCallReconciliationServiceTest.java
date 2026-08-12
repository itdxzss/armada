package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 已提交逐成员结果超时后的释放行为测试。 */
class PullTaskPullCallReconciliationServiceTest {

    private static final long CUTOFF = 35_000L;
    private static final long NOW = 50_000L;

    private PullTaskPullCallMemberAttemptMapper attemptMapper;
    private PullTaskPullCallParticipantResultService participantResultService;
    private PullTaskPullCallReconciliationService service;

    @BeforeEach
    void setUp() {
        attemptMapper = mock(PullTaskPullCallMemberAttemptMapper.class);
        participantResultService = mock(PullTaskPullCallParticipantResultService.class);
        service = new PullTaskPullCallReconciliationService(
                new PullTaskUnknownResultResources(
                        mock(PullTaskAccountActionMapper.class),
                        mock(PullTaskPullCallMapper.class), attemptMapper,
                        mock(PullTaskMaterialMemberMapper.class),
                        mock(PullTaskGroupAccountMapper.class)),
                participantResultService);
    }

    @Test
    void fifteenSecondWindowReleasesExplicitUnknownAndMissingCallbackWithoutRosterQuery() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(35_000L);
        PullTaskPullCallMemberAttempt explicitUnknown = attempt(
                41L, "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        PullTaskPullCallMemberAttempt missing = attempt(42L, null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(participantResultService.settleUncertain(any())).thenReturn(true);

        PullTaskUnknownResultReconciliationStats stats = service.reconcile(
                execution, call, List.of(explicitUnknown, missing), List.of(), CUTOFF, NOW);

        assertThat(stats).isEqualTo(new PullTaskUnknownResultReconciliationStats(0, 2));
        ArgumentCaptor<PullTaskUncertainParticipantSettlement> settlement =
                ArgumentCaptor.forClass(PullTaskUncertainParticipantSettlement.class);
        verify(participantResultService, org.mockito.Mockito.times(2))
                .settleUncertain(settlement.capture());
        assertThat(settlement.getAllValues())
                .allSatisfy(row -> assertThat(row.observation())
                        .isEqualTo(PullTaskRosterObservation.UNCONFIRMED));
        ArgumentCaptor<PullTaskParticipantAttemptTransition> missingTransition =
                ArgumentCaptor.forClass(PullTaskParticipantAttemptTransition.class);
        verify(attemptMapper).transition(missingTransition.capture());
        assertThat(missingTransition.getValue().target().protocolOutcome()).isEqualTo("UNKNOWN");
        assertThat(missingTransition.getValue().target().executionState())
                .isEqualTo(PullTaskParticipantExecutionState.UNCERTAIN);
    }

    @Test
    void freshSubmittedCallRemainsOpenUntilFifteenSecondWindowExpires() {
        PullTaskPullCall call = call(CUTOFF + 1L);

        assertThat(service.reconcile(
                execution(), call, List.of(attempt(41L, null, null)),
                List.of(), CUTOFF, NOW))
                .isEqualTo(PullTaskUnknownResultReconciliationStats.empty());

        verify(attemptMapper, never()).transition(any());
        verify(participantResultService, never()).settleUncertain(any());
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(21L);
        execution.setTenantId(7L);
        execution.setTaskId(11L);
        return execution;
    }

    private static PullTaskPullCall call(long submittedAt) {
        PullTaskPullCall call = new PullTaskPullCall();
        call.setId(31L);
        call.setTaskId(11L);
        call.setGroupExecutionId(21L);
        call.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        call.setSubmittedAt(submittedAt);
        return call;
    }

    private static PullTaskPullCallMemberAttempt attempt(
            long id,
            String outcome,
            PullTaskParticipantExecutionState executionState) {
        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setId(id);
        attempt.setTaskId(11L);
        attempt.setGroupExecutionId(21L);
        attempt.setPullCallId(31L);
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(id + 100L);
        attempt.setTargetJid("86138000000" + id + "@s.whatsapp.net");
        attempt.setFailureCountBefore(0L);
        attempt.setLifecycleStatus(PullTaskParticipantAttemptStatus.SUBMITTED.code());
        attempt.setProtocolOutcome(outcome);
        attempt.setExecutionState(executionState);
        return attempt;
    }
}
