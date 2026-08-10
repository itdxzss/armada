package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.dto.PullTaskPlannedCallPrune;
import com.armada.task.model.dto.PullTaskUncertainParticipantSettlement;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskRosterObservation;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.scheduler.PullTaskPullWaveProgressService;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class PullTaskPullCallParticipantResultServiceTest {

    private static final long ATTEMPT_ID = 41L;
    private static final long PARTICIPANT_ID = 51L;
    private static final String TARGET = "8613800000001@s.whatsapp.net";

    private PullTaskPullCallMapper callMapper;
    private PullTaskPullCallMemberAttemptMapper attemptMapper;
    private PullTaskMaterialMemberMapper materialMapper;
    private PullTaskGroupAccountMapper accountMapper;
    private PullTaskGroupExecutionMapper executionMapper;
    private PullTaskStandardSettingMapper settingMapper;
    private PullTaskStickyPullerTransactionService stickyPullers;
    private PullTaskGroupExecutionFailureService groupFailure;
    private PullTaskPullWaveProgressService waveProgress;
    private PullTaskPullCallParticipantResultService service;

    @BeforeEach
    void setUp() {
        callMapper = mock(PullTaskPullCallMapper.class);
        attemptMapper = mock(PullTaskPullCallMemberAttemptMapper.class);
        materialMapper = mock(PullTaskMaterialMemberMapper.class);
        accountMapper = mock(PullTaskGroupAccountMapper.class);
        executionMapper = mock(PullTaskGroupExecutionMapper.class);
        settingMapper = mock(PullTaskStandardSettingMapper.class);
        stickyPullers = mock(PullTaskStickyPullerTransactionService.class);
        groupFailure = mock(PullTaskGroupExecutionFailureService.class);
        waveProgress = mock(PullTaskPullWaveProgressService.class);
        service = new PullTaskPullCallParticipantResultService(
                new PullTaskUnknownResultResources(
                        mock(PullTaskAccountActionMapper.class), callMapper, attemptMapper,
                        materialMapper, accountMapper),
                executionMapper, settingMapper,
                new PullTaskPullCallResultCoordination(
                        stickyPullers, groupFailure, waveProgress));
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call());
        when(executionMapper.selectById(21L)).thenReturn(execution());
        when(accountMapper.selectByExecutionAndRole(
                21L, PullTaskGroupAccountRole.PULLER.code()))
                .thenReturn(List.of(puller()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @EnumSource(PullTaskParticipantType.class)
    void explicitSuccessClosesAttemptAndPromotesAggregate(
            PullTaskParticipantType type) {
        stubAttempt(type, 2L, PullTaskParticipantAttemptStatus.SUBMITTED,
                null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        stubAggregateChange(type, 1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        PullTaskParticipantAttemptTransition attempt = capturedAttempt();
        assertThat(attempt.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        assertThat(attempt.target().protocolOutcome()).isEqualTo("SUCCESS");
        assertThat(attempt.target().executionState())
                .isEqualTo(PullTaskParticipantExecutionState.STARTED);
        assertThat(attempt.target().activeSlot()).isNull();
        PullTaskParticipantAggregateTransition aggregate = capturedAggregate(type);
        assertThat(aggregate.target().status()).isEqualTo(successStatus(type));
        assertThat(aggregate.target().failureCount()).isEqualTo(2L);
        assertThat(aggregate.target().pullCallId()).isEqualTo(31L);
        assertThat(aggregate.target().activeAttemptId()).isNull();
    }

    @ParameterizedTest(name = "{0} explicit failure count {1}")
    @MethodSource("failureCases")
    void everyExplicitFailureConsumesOneCountAndOnlyFourthIsTerminal(
            PullTaskParticipantType type, long failureCountBefore) {
        stubAttempt(type, failureCountBefore,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        stubAggregateChange(type, 1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        PullTaskParticipantAttemptTransition attempt = capturedAttempt();
        assertThat(attempt.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        PullTaskParticipantAggregateTransition aggregate = capturedAggregate(type);
        long failureCount = failureCountBefore + 1;
        assertThat(aggregate.target().failureCount()).isEqualTo(failureCount);
        assertThat(aggregate.target().status()).isEqualTo(
                failureCount < 4 ? pendingStatus(type) : failedStatus(type));
        assertThat(aggregate.target().pullCallId())
                .isEqualTo(failureCount < 4 ? null : 31L);
        assertThat(aggregate.target().activeAttemptId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(PullTaskParticipantType.class)
    void unknownNotStartedReleasesImmediatelyWithoutConsumingFailure(
            PullTaskParticipantType type) {
        stubAttempt(type, 2L, PullTaskParticipantAttemptStatus.SUBMITTED,
                null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        stubAggregateChange(type, 1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.NOT_STARTED, true))).isTrue();

        PullTaskParticipantAttemptTransition attempt = capturedAttempt();
        assertThat(attempt.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.RELEASED.code());
        assertThat(attempt.target().releasedAt()).isEqualTo(5_000L);
        PullTaskParticipantAggregateTransition aggregate = capturedAggregate(type);
        assertThat(aggregate.target().status()).isEqualTo(pendingStatus(type));
        assertThat(aggregate.target().failureCount()).isEqualTo(2L);
        assertThat(aggregate.target().pullCallId()).isNull();
        assertThat(aggregate.target().activeAttemptId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(PullTaskParticipantType.class)
    void unknownUncertainRemainsSubmittedForReconciliation(
            PullTaskParticipantType type) {
        stubAttempt(type, 1L, PullTaskParticipantAttemptStatus.SUBMITTED,
                null, null);
        when(attemptMapper.transition(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.UNCERTAIN, true))).isTrue();

        PullTaskParticipantAttemptTransition transition = capturedAttempt();
        assertThat(transition.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.SUBMITTED.code());
        assertThat(transition.target().protocolOutcome()).isEqualTo("UNKNOWN");
        assertThat(transition.target().executionState())
                .isEqualTo(PullTaskParticipantExecutionState.UNCERTAIN);
        assertThat(transition.target().activeSlot()).isEqualTo(1);
        verify(materialMapper, never()).transitionPullAttempt(any());
        verify(accountMapper, never()).transitionMembershipAttempt(any());
    }

    @Test
    void duplicateExplicitCallbackDoesNotIncrementTwice() {
        stubAttempt(PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.CLOSED,
                "FAILED", PullTaskParticipantExecutionState.STARTED);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED, true))).isTrue();

        verify(attemptMapper, never()).transition(any());
        verify(materialMapper, never()).transitionPullAttempt(any());
    }

    @Test
    void targetOutsideFrozenCallIsRejected() {
        when(attemptMapper.selectByCallAndTarget(31L, TARGET)).thenReturn(null);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isFalse();

        verify(attemptMapper, never()).transition(any());
        verify(materialMapper, never()).transitionPullAttempt(any());
        verify(accountMapper, never()).transitionMembershipAttempt(any());
    }

    @Test
    void lastTerminalAttemptClosesCallAndWakesCollectingWaveOnce() {
        PullTaskPullCall call = call();
        call.setPullWaveId(71L);
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(0);
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        PullTaskPullCallMemberAttempt closed = copyAttempt(
                attempt, PullTaskParticipantAttemptStatus.CLOSED);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);
        when(attemptMapper.selectByCall(31L)).thenReturn(List.of(closed));
        when(callMapper.transitionResult(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        ArgumentCaptor<PullTaskFactTransition> callChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(callMapper).transitionResult(callChange.capture());
        assertThat(callChange.getValue().targetStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        verify(waveProgress).wakeCollecting(7L, 21L, 71L, 5_000L);
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void uncertainAttemptPreventsCallClosure() {
        PullTaskPullCall call = call();
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(0);
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(attemptMapper.selectByCall(31L)).thenReturn(List.of(attempt));

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.UNCERTAIN, true))).isTrue();

        verify(callMapper, never()).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void releasedAttemptLateFailureUpdatesHistoryOnly() {
        stubAttempt(PullTaskParticipantType.MATERIAL, 1L,
                PullTaskParticipantAttemptStatus.RELEASED,
                "UNKNOWN", PullTaskParticipantExecutionState.NOT_STARTED);
        when(attemptMapper.transition(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        assertThat(capturedAttempt().target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        verify(materialMapper, never()).transitionPullAttempt(any());
        verify(materialMapper, never()).promotePullSuccess(any());
    }

    @Test
    void releasedAttemptLateSuccessMonotonicallyPromotesAggregate() {
        stubAttempt(PullTaskParticipantType.MATERIAL, 3L,
                PullTaskParticipantAttemptStatus.RELEASED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        PullTaskMaterialMember aggregate = aggregateMaterial(
                PullTaskMaterialPullStatus.FAILED.code(), 4L, null);
        when(materialMapper.selectByExecution(21L)).thenReturn(List.of(aggregate));
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.promotePullSuccess(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        ArgumentCaptor<PullTaskParticipantAggregateTransition> captor =
                ArgumentCaptor.forClass(PullTaskParticipantAggregateTransition.class);
        verify(materialMapper).promotePullSuccess(captor.capture());
        assertThat(captor.getValue().target().status())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(captor.getValue().target().failureCount()).isEqualTo(4L);
        verify(materialMapper, never()).transitionPullAttempt(any());
    }

    @Test
    void newerAttemptFailureCannotDowngradeAggregatePromotedByLateSuccess() {
        stubAttempt(PullTaskParticipantType.MATERIAL, 1L,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        PullTaskMaterialMember aggregate = aggregateMaterial(
                PullTaskMaterialPullStatus.SUCCESS.code(), 1L, ATTEMPT_ID);
        when(materialMapper.selectByExecution(21L)).thenReturn(List.of(aggregate));
        when(attemptMapper.transition(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED, true))).isTrue();

        assertThat(capturedAttempt().target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        verify(materialMapper, never()).transitionPullAttempt(any());
        verify(materialMapper, never()).promotePullSuccess(any());
    }

    @Test
    void lateSuccessPrunesOnlyItsNewerPlannedAttemptBeforePromotion() {
        PullTaskPullCallMemberAttempt oldAttempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 1L,
                PullTaskParticipantAttemptStatus.RELEASED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        PullTaskPullCallMemberAttempt newerMaterial = copyAttempt(
                oldAttempt, PullTaskParticipantAttemptStatus.PLANNED);
        newerMaterial.setId(42L);
        newerMaterial.setPullCallId(32L);
        newerMaterial.setAttemptNo(2);
        PullTaskPullCallMemberAttempt newerStation = copyAttempt(
                oldAttempt, PullTaskParticipantAttemptStatus.PLANNED);
        newerStation.setId(43L);
        newerStation.setPullCallId(32L);
        newerStation.setParticipantType(PullTaskParticipantType.STATION.code());
        newerStation.setParticipantRefId(52L);
        PullTaskMaterialMember material = aggregateMaterial(
                PullTaskMaterialPullStatus.SUBMITTED.code(), 1L, 42L);
        PullTaskPullCall newerCall = call();
        newerCall.setId(32L);
        newerCall.setCallStatus(PullTaskPullCallStatus.PLANNED.code());
        newerCall.setPlannedMaterialCount(1);
        newerCall.setPlannedStationCount(1);
        when(materialMapper.selectByExecution(21L)).thenReturn(List.of(material));
        when(attemptMapper.selectById(42L)).thenReturn(newerMaterial);
        when(callMapper.selectByExecution(21L)).thenReturn(List.of(call(), newerCall));
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);
        when(materialMapper.promotePullSuccess(any())).thenReturn(1);
        when(callMapper.prunePlannedParticipant(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        ArgumentCaptor<PullTaskParticipantAttemptTransition> attempts =
                ArgumentCaptor.forClass(PullTaskParticipantAttemptTransition.class);
        verify(attemptMapper, org.mockito.Mockito.times(2)).transition(attempts.capture());
        assertThat(attempts.getAllValues())
                .extracting(row -> row.target().lifecycleStatus())
                .containsExactly(
                        PullTaskParticipantAttemptStatus.CLOSED.code(),
                        PullTaskParticipantAttemptStatus.CANCELED.code());
        ArgumentCaptor<PullTaskPlannedCallPrune> prune =
                ArgumentCaptor.forClass(PullTaskPlannedCallPrune.class);
        verify(callMapper).prunePlannedParticipant(prune.capture());
        assertThat(prune.getValue().pullCallId()).isEqualTo(32L);
        assertThat(prune.getValue().participantType())
                .isEqualTo(PullTaskParticipantType.MATERIAL.code());
        verify(accountMapper, never()).transitionMembershipAttempt(any());
        verify(materialMapper).promotePullSuccess(any());
    }

    @Test
    void needReauthMarksOfflineAndInvalidatesOnlyTheCallGeneration() {
        stubAccountFailure("NEED_REAUTH");

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED,
                false, "NEED_REAUTH"))).isTrue();

        verify(accountMapper).markUnavailable(
                61L, PullTaskGroupAccountAvailability.OFFLINE.code(),
                "NEED_REAUTH", null, 5_000L);
        verify(stickyPullers).invalidateIfCurrent(
                argThat(row -> row.getId() == 21L),
                argThat(row -> row.getId() == 31L),
                eq("NEED_REAUTH"), eq(5_000L));
    }

    @Test
    void rateLimitedMarksRiskCooldownAndInvalidatesOnlyTheCallGeneration() {
        stubAccountFailure("RATE_LIMITED");
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setPullerRiskMinutes(5);
        when(settingMapper.selectByTaskId(11L)).thenReturn(setting);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.NOT_STARTED,
                true, "RATE_LIMITED"))).isTrue();

        verify(accountMapper).markUnavailable(
                61L, PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                "RATE_LIMITED", 305_000L, 5_000L);
        verify(stickyPullers).invalidateIfCurrent(
                argThat(row -> row.getId() == 21L),
                argThat(row -> row.getId() == 31L),
                eq("RATE_LIMITED"), eq(5_000L));
    }

    @Test
    void participantPermissionResultDoesNotRemovePullerAfterBatchStarted() {
        stubAccountFailure("GROUP_PERMISSION_DENIED");

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED,
                false, "GROUP_PERMISSION_DENIED"))).isTrue();

        verify(accountMapper, never()).markUnavailable(
                anyLong(), anyInt(), any(), any(), anyLong());
        verify(stickyPullers, never()).invalidateIfCurrent(
                any(), any(), any(), anyLong());
    }

    @Test
    void accountPermissionDeniedBeforeBatchStartsRemovesPullerOnlyFromExecution() {
        stubAccountFailure("GROUP_PERMISSION_DENIED");

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.UNKNOWN,
                PullTaskParticipantExecutionState.NOT_STARTED,
                true, "GROUP_PERMISSION_DENIED"))).isTrue();

        verify(accountMapper).markUnavailable(
                61L, PullTaskGroupAccountAvailability.REMOVED.code(),
                "GROUP_PERMISSION_DENIED", null, 5_000L);
        verify(stickyPullers).invalidateIfCurrent(
                argThat(row -> row.getId() == 21L),
                argThat(row -> row.getId() == 31L),
                eq("GROUP_PERMISSION_DENIED"), eq(5_000L));
    }

    @Test
    void groupUnavailableTerminatesExecutionWithoutRotatingPuller() {
        stubAccountFailure("GROUP_UNAVAILABLE");

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED,
                false, "GROUP_UNAVAILABLE"))).isTrue();

        verify(groupFailure).terminate(
                7L, 21L,
                com.armada.task.model.enums.PullTaskExecutionReasonCode.GROUP_UNAVAILABLE,
                5_000L);
        verify(stickyPullers, never()).invalidateIfCurrent(
                any(), any(), any(), anyLong());
    }

    @Test
    void rosterPresenceConfirmsUncertainAttemptAsSuccess() {
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 2L,
                PullTaskParticipantAttemptStatus.SUBMITTED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);

        assertThat(service.settleUncertain(settlement(
                attempt, PullTaskRosterObservation.PRESENT, 6_000L))).isTrue();

        PullTaskParticipantAttemptTransition attemptChange = capturedAttempt();
        assertThat(attemptChange.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        assertThat(attemptChange.target().protocolOutcome()).isEqualTo("SUCCESS");
        PullTaskParticipantAggregateTransition aggregate =
                capturedAggregate(PullTaskParticipantType.MATERIAL);
        assertThat(aggregate.target().status())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(aggregate.target().failureCount()).isEqualTo(2L);
    }

    @Test
    void rosterAbsenceReleasesUncertainAttemptWithoutFailureCount() {
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.STATION, 2L,
                PullTaskParticipantAttemptStatus.SUBMITTED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(accountMapper.transitionMembershipAttempt(any())).thenReturn(1);

        assertThat(service.settleUncertain(settlement(
                attempt, PullTaskRosterObservation.ABSENT, 6_000L))).isTrue();

        PullTaskParticipantAttemptTransition attemptChange = capturedAttempt();
        assertThat(attemptChange.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.RELEASED.code());
        PullTaskParticipantAggregateTransition aggregate =
                capturedAggregate(PullTaskParticipantType.STATION);
        assertThat(aggregate.target().status())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        assertThat(aggregate.target().failureCount()).isEqualTo(2L);
        assertThat(aggregate.target().pullCallId()).isNull();
    }

    @Test
    void rosterUnavailableClosesAttemptAsFinalUnknownWithoutFailureCount() {
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 2L,
                PullTaskParticipantAttemptStatus.SUBMITTED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);

        assertThat(service.settleUncertain(settlement(
                attempt, PullTaskRosterObservation.UNAVAILABLE, 6_000L))).isTrue();

        PullTaskParticipantAttemptTransition attemptChange = capturedAttempt();
        assertThat(attemptChange.target().lifecycleStatus())
                .isEqualTo(PullTaskParticipantAttemptStatus.CLOSED.code());
        assertThat(attemptChange.target().protocolOutcome()).isEqualTo("UNKNOWN");
        PullTaskParticipantAggregateTransition aggregate =
                capturedAggregate(PullTaskParticipantType.MATERIAL);
        assertThat(aggregate.target().status())
                .isEqualTo(PullTaskMaterialPullStatus.UNKNOWN.code());
        assertThat(aggregate.target().failureCount()).isEqualTo(2L);
        assertThat(aggregate.target().activeAttemptId()).isNull();
    }

    @Test
    void lateSuccessAfterFinalUnknownPromotesFactWithoutWakingSettledWave() {
        PullTaskPullCall settledCall = call();
        settledCall.setCallStatus(PullTaskPullCallStatus.WRITTEN_BACK.code());
        settledCall.setPullWaveId(71L);
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(settledCall);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.CLOSED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        attempt.setReasonCode("ROSTER_QUERY_UNAVAILABLE");
        PullTaskMaterialMember material = aggregateMaterial(
                PullTaskMaterialPullStatus.UNKNOWN.code(), 0L, null);
        when(materialMapper.selectByExecution(21L)).thenReturn(List.of(material));
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.promotePullSuccess(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        verify(materialMapper).promotePullSuccess(any());
        verify(waveProgress, never()).wakeCollecting(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void lateFailureAfterFinalUnknownUpdatesOnlyOldAttemptFact() {
        PullTaskPullCall settledCall = call();
        settledCall.setCallStatus(PullTaskPullCallStatus.WRITTEN_BACK.code());
        settledCall.setPullWaveId(71L);
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(settledCall);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.CLOSED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        attempt.setReasonCode("ROSTER_QUERY_UNAVAILABLE");
        when(attemptMapper.transition(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        verify(materialMapper, never()).transitionPullAttempt(any());
        verify(materialMapper, never()).promotePullSuccess(any());
        verify(waveProgress, never()).wakeCollecting(anyLong(), anyLong(), anyLong(), anyLong());
    }

    private PullTaskPullCallMemberAttempt stubAttempt(
            PullTaskParticipantType type,
            long failureCountBefore,
            PullTaskParticipantAttemptStatus status,
            String outcome,
            PullTaskParticipantExecutionState executionState) {
        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setId(ATTEMPT_ID);
        attempt.setTaskId(11L);
        attempt.setGroupExecutionId(21L);
        attempt.setPullCallId(31L);
        attempt.setParticipantType(type.code());
        attempt.setParticipantRefId(PARTICIPANT_ID);
        attempt.setTargetJid(TARGET);
        attempt.setPullerGroupAccountId(61L);
        attempt.setAttemptNo((int) failureCountBefore + 1);
        attempt.setFailureCountBefore(failureCountBefore);
        attempt.setLifecycleStatus(status.code());
        attempt.setActiveSlot(PullTaskParticipantAttemptStatus.active(status.code()) ? 1 : null);
        attempt.setProtocolOutcome(outcome);
        attempt.setExecutionState(executionState);
        when(attemptMapper.selectByCallAndTarget(31L, TARGET)).thenReturn(attempt);
        return attempt;
    }

    private void stubAggregateChange(PullTaskParticipantType type, int changed) {
        if (type == PullTaskParticipantType.MATERIAL) {
            when(materialMapper.transitionPullAttempt(any())).thenReturn(changed);
        } else {
            when(accountMapper.transitionMembershipAttempt(any())).thenReturn(changed);
        }
    }

    private void stubAccountFailure(String reasonCode) {
        stubAttempt(PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);
        when(accountMapper.markUnavailable(
                anyLong(), anyInt(), any(), any(), anyLong())).thenReturn(1);
        when(stickyPullers.invalidateIfCurrent(
                any(), any(), org.mockito.ArgumentMatchers.eq(reasonCode), anyLong()))
                .thenReturn(true);
    }

    private PullTaskParticipantAttemptTransition capturedAttempt() {
        ArgumentCaptor<PullTaskParticipantAttemptTransition> captor =
                ArgumentCaptor.forClass(PullTaskParticipantAttemptTransition.class);
        verify(attemptMapper).transition(captor.capture());
        return captor.getValue();
    }

    private PullTaskParticipantAggregateTransition capturedAggregate(
            PullTaskParticipantType type) {
        ArgumentCaptor<PullTaskParticipantAggregateTransition> captor =
                ArgumentCaptor.forClass(PullTaskParticipantAggregateTransition.class);
        if (type == PullTaskParticipantType.MATERIAL) {
            verify(materialMapper).transitionPullAttempt(captor.capture());
        } else {
            verify(accountMapper).transitionMembershipAttempt(captor.capture());
        }
        return captor.getValue();
    }

    private static Stream<Arguments> failureCases() {
        return Stream.of(PullTaskParticipantType.values())
                .flatMap(type -> IntStream.range(0, 4)
                        .mapToObj(count -> Arguments.of(type, (long) count)));
    }

    private static int pendingStatus(PullTaskParticipantType type) {
        return type == PullTaskParticipantType.MATERIAL
                ? PullTaskMaterialPullStatus.UNCONSUMED.code()
                : PullTaskGroupAccountMembershipStatus.NOT_JOINED.code();
    }

    private static int successStatus(PullTaskParticipantType type) {
        return type == PullTaskParticipantType.MATERIAL
                ? PullTaskMaterialPullStatus.SUCCESS.code()
                : PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
    }

    private static int failedStatus(PullTaskParticipantType type) {
        return type == PullTaskParticipantType.MATERIAL
                ? PullTaskMaterialPullStatus.FAILED.code()
                : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
    }

    private static PullTaskPullCall call() {
        PullTaskPullCall call = new PullTaskPullCall();
        call.setId(31L);
        call.setTaskId(11L);
        call.setGroupExecutionId(21L);
        call.setPullerGroupAccountId(61L);
        call.setPullerAccountId(71L);
        call.setPullerAssignmentSeq(1L);
        call.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        call.setCommandId("cmd-call");
        return call;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(21L);
        execution.setTenantId(7L);
        execution.setTaskId(11L);
        execution.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        execution.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        execution.setVersion(3);
        execution.setActivePullerGroupAccountId(61L);
        execution.setPullerAssignmentSeq(1L);
        return execution;
    }

    private static PullTaskPullCallMemberAttempt copyAttempt(
            PullTaskPullCallMemberAttempt source,
            PullTaskParticipantAttemptStatus status) {
        PullTaskPullCallMemberAttempt copy = new PullTaskPullCallMemberAttempt();
        copy.setId(source.getId());
        copy.setTaskId(source.getTaskId());
        copy.setGroupExecutionId(source.getGroupExecutionId());
        copy.setPullCallId(source.getPullCallId());
        copy.setParticipantType(source.getParticipantType());
        copy.setParticipantRefId(source.getParticipantRefId());
        copy.setTargetJid(source.getTargetJid());
        copy.setPullerGroupAccountId(source.getPullerGroupAccountId());
        copy.setFailureCountBefore(source.getFailureCountBefore());
        copy.setLifecycleStatus(status.code());
        return copy;
    }

    private static PullTaskMaterialMember aggregateMaterial(
            int status, long failureCount, Long activeAttemptId) {
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setId(PARTICIPANT_ID);
        material.setGroupExecutionId(21L);
        material.setPullStatus(status);
        material.setPullFailureCount(failureCount);
        material.setActivePullAttemptId(activeAttemptId);
        return material;
    }

    private static PullTaskGroupAccount aggregateStation(long id, long activeAttemptId) {
        PullTaskGroupAccount station = new PullTaskGroupAccount();
        station.setId(id);
        station.setMembershipStatus(PullTaskGroupAccountMembershipStatus.JOINING.code());
        station.setMembershipFailureCount(0L);
        station.setActivePullAttemptId(activeAttemptId);
        return station;
    }

    private static PullTaskGroupAccount puller() {
        PullTaskGroupAccount puller = new PullTaskGroupAccount();
        puller.setId(61L);
        puller.setTaskId(11L);
        puller.setGroupExecutionId(21L);
        puller.setAccountId(71L);
        puller.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        puller.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        return puller;
    }

    private static PullTaskBatchParticipantCallback callback(
            PullTaskBatchParticipantProtocolOutcome outcome,
            PullTaskParticipantExecutionState executionState,
            boolean retryable) {
        return callback(outcome, executionState, retryable, null);
    }

    private static PullTaskBatchParticipantCallback callback(
            PullTaskBatchParticipantProtocolOutcome outcome,
            PullTaskParticipantExecutionState executionState,
            boolean retryable,
            String reasonCode) {
        return new PullTaskBatchParticipantCallback(
                7L, 11L, 21L, 31L, 71L, "protocol-71", "cmd-call", 1,
                TARGET, outcome, executionState, reasonCode, null, retryable, 5_000L);
    }

    private static PullTaskUncertainParticipantSettlement settlement(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskRosterObservation observation,
            long now) {
        return new PullTaskUncertainParticipantSettlement(
                new PullTaskUncertainParticipantSettlement.Context(
                        7L, call(), execution()),
                attempt, observation, now);
    }
}
