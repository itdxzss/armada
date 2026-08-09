package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallRosterCheckStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.service.impl.PullTaskPullCallParticipantResultService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PullTaskPullCallReconciliationServiceTest {

    private static final long CUTOFF = 40_000L;
    private static final long NOW = 50_000L;

    private PullTaskPullCallMapper callMapper;
    private PullTaskPullCallMemberAttemptMapper attemptMapper;
    private AccountProtocolLookupService accountLookup;
    private GroupMemberListPort memberListPort;
    private PullTaskPullCallParticipantResultService participantResultService;
    private PullTaskPullCallReconciliationService service;

    @BeforeEach
    void setUp() {
        callMapper = mock(PullTaskPullCallMapper.class);
        attemptMapper = mock(PullTaskPullCallMemberAttemptMapper.class);
        accountLookup = mock(AccountProtocolLookupService.class);
        memberListPort = mock(GroupMemberListPort.class);
        participantResultService = mock(PullTaskPullCallParticipantResultService.class);
        service = new PullTaskPullCallReconciliationService(
                new PullTaskUnknownResultResources(
                        mock(PullTaskAccountActionMapper.class), callMapper, attemptMapper,
                        mock(PullTaskMaterialMemberMapper.class),
                        mock(PullTaskGroupAccountMapper.class)),
                accountLookup, memberListPort, participantResultService);
    }

    @Test
    void oneRosterQuerySettlesEveryUncertainAndMissingTargetLocally() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        PullTaskPullCallMemberAttempt present = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);
        PullTaskPullCallMemberAttempt missing = attempt(
                42L, "8613800000002@s.whatsapp.net", null, null,
                PullTaskParticipantAttemptStatus.SUBMITTED);
        PullTaskPullCallMemberAttempt notStarted = attempt(
                43L, "8613800000003@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.NOT_STARTED,
                PullTaskParticipantAttemptStatus.RELEASED);
        PullTaskGroupAccount manager = manager();
        when(callMapper.claimRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), NOW)).thenReturn(1);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(accountLookup.findActiveProtocolRefs(List.of(71L)))
                .thenReturn(List.of(protocol()));
        when(memberListPort.list(any())).thenReturn(List.of(
                new GroupParticipantResult(
                        present.getTargetJid(), "8613800000001", false, false, null)));
        when(participantResultService.settleUncertain(
                settlement(execution, call, present, PullTaskRosterObservation.PRESENT)))
                .thenReturn(true);
        when(participantResultService.settleUncertain(
                settlement(execution, call, missing, PullTaskRosterObservation.ABSENT)))
                .thenReturn(true);
        when(callMapper.finishRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                PullTaskPullCallRosterCheckStatus.SUCCEEDED.code(), NOW)).thenReturn(1);

        PullTaskUnknownResultReconciliationStats stats = service.reconcile(
                execution, call, List.of(present, missing, notStarted),
                List.of(manager), CUTOFF, NOW);

        verify(memberListPort).list(any());
        verify(participantResultService).settleUncertain(
                settlement(execution, call, present, PullTaskRosterObservation.PRESENT));
        verify(participantResultService).settleUncertain(
                settlement(execution, call, missing, PullTaskRosterObservation.ABSENT));
        verify(participantResultService, never()).settleUncertain(
                settlement(execution, call, notStarted, PullTaskRosterObservation.ABSENT));
        verify(attemptMapper).transition(any());
        assertThat(stats).isEqualTo(new PullTaskUnknownResultReconciliationStats(1, 1));
    }

    @Test
    void losingRosterClaimNeverCallsProtocolOrSettlesTargets() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);
        when(callMapper.claimRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), NOW)).thenReturn(0);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(manager()), CUTOFF, NOW))
                .isEqualTo(PullTaskUnknownResultReconciliationStats.empty());

        verify(memberListPort, never()).list(any());
        verify(participantResultService, never()).settleUncertain(any());
    }

    @Test
    void scanBeforeSixtySecondProtectionExpiresDoesNothing() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        call.setSubmittedAt(CUTOFF + 1);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(manager()), CUTOFF, NOW))
                .isEqualTo(PullTaskUnknownResultReconciliationStats.empty());

        verify(callMapper, never()).claimRosterCheck(
                anyLong(), anyInt(), anyInt(), anyLong());
        verify(memberListPort, never()).list(any());
    }

    @Test
    void noUsableInGroupAccountSkipsQueryAndClosesFinalUnknown() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);
        when(callMapper.claimRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), NOW)).thenReturn(1);
        when(participantResultService.settleUncertain(
                settlement(execution, call, attempt, PullTaskRosterObservation.UNAVAILABLE)))
                .thenReturn(true);
        when(callMapper.finishRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                PullTaskPullCallRosterCheckStatus.SKIPPED.code(), NOW)).thenReturn(1);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(), CUTOFF, NOW))
                .isEqualTo(new PullTaskUnknownResultReconciliationStats(0, 1));

        verify(memberListPort, never()).list(any());
        verify(participantResultService).settleUncertain(
                settlement(execution, call, attempt, PullTaskRosterObservation.UNAVAILABLE));
    }

    @Test
    void httpFailureClosesEveryUncertainAttemptAsFinalUnknown() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);
        when(callMapper.claimRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.NOT_STARTED.code(),
                PullTaskPullCallRosterCheckStatus.CLAIMED.code(), NOW)).thenReturn(1);
        when(accountLookup.findActiveProtocolRefs(List.of(71L)))
                .thenReturn(List.of(protocol()));
        when(memberListPort.list(any())).thenThrow(new IllegalStateException("http failed"));
        when(participantResultService.settleUncertain(
                settlement(execution, call, attempt, PullTaskRosterObservation.UNAVAILABLE)))
                .thenReturn(true);
        when(callMapper.finishRosterCheck(
                31L, PullTaskPullCallRosterCheckStatus.CLAIMED.code(),
                PullTaskPullCallRosterCheckStatus.FAILED.code(), NOW)).thenReturn(1);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(manager()), CUTOFF, NOW))
                .isEqualTo(new PullTaskUnknownResultReconciliationStats(0, 1));

        verify(participantResultService).settleUncertain(
                settlement(execution, call, attempt, PullTaskRosterObservation.UNAVAILABLE));
    }

    @Test
    void notStartedAttemptNeverTriggersRosterQuery() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(PullTaskPullCallRosterCheckStatus.NOT_STARTED, null);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.NOT_STARTED,
                PullTaskParticipantAttemptStatus.SUBMITTED);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(manager()), CUTOFF, NOW))
                .isEqualTo(PullTaskUnknownResultReconciliationStats.empty());

        verify(memberListPort, never()).list(any());
        verify(participantResultService, never()).settleUncertain(any());
    }

    @Test
    void freshDurableClaimWaitsWithoutRepeatingRosterQuery() {
        PullTaskGroupExecution execution = execution();
        PullTaskPullCall call = call(
                PullTaskPullCallRosterCheckStatus.CLAIMED, CUTOFF + 1);
        PullTaskPullCallMemberAttempt attempt = attempt(
                41L, "8613800000001@s.whatsapp.net", "UNKNOWN",
                PullTaskParticipantExecutionState.UNCERTAIN,
                PullTaskParticipantAttemptStatus.SUBMITTED);

        assertThat(service.reconcile(
                execution, call, List.of(attempt), List.of(manager()), CUTOFF, NOW))
                .isEqualTo(PullTaskUnknownResultReconciliationStats.empty());

        verify(memberListPort, never()).list(any());
        verify(participantResultService, never()).settleUncertain(any());
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(21L);
        execution.setTenantId(7L);
        execution.setTaskId(11L);
        execution.setGroupJid("120363group@g.us");
        return execution;
    }

    private static PullTaskPullCall call(
            PullTaskPullCallRosterCheckStatus rosterStatus,
            Long rosterStartedAt) {
        PullTaskPullCall call = new PullTaskPullCall();
        call.setId(31L);
        call.setTaskId(11L);
        call.setGroupExecutionId(21L);
        call.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        call.setSubmittedAt(20_000L);
        call.setRosterCheckStatus(rosterStatus.code());
        call.setRosterCheckStartedAt(rosterStartedAt);
        return call;
    }

    private static PullTaskPullCallMemberAttempt attempt(
            long id,
            String targetJid,
            String outcome,
            PullTaskParticipantExecutionState executionState,
            PullTaskParticipantAttemptStatus status) {
        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setId(id);
        attempt.setTaskId(11L);
        attempt.setGroupExecutionId(21L);
        attempt.setPullCallId(31L);
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(id + 100);
        attempt.setTargetJid(targetJid);
        attempt.setFailureCountBefore(0L);
        attempt.setLifecycleStatus(status.code());
        attempt.setProtocolOutcome(outcome);
        attempt.setExecutionState(executionState);
        return attempt;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount manager = new PullTaskGroupAccount();
        manager.setId(61L);
        manager.setAccountId(71L);
        manager.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        manager.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        return manager;
    }

    private static ProtocolAccountRef protocol() {
        return new ProtocolAccountRef(
                71L, ProtocolBackend.WEB, "protocol-71", "8613800000071");
    }

    private static PullTaskUncertainParticipantSettlement settlement(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            PullTaskPullCallMemberAttempt attempt,
            PullTaskRosterObservation observation) {
        return new PullTaskUncertainParticipantSettlement(
                new PullTaskUncertainParticipantSettlement.Context(7L, call, execution),
                attempt, observation, NOW);
    }
}
