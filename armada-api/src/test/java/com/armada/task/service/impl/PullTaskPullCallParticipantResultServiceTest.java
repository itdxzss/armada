package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
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
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
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
    private PullTaskOperationDelayPolicy delayPolicy;
    private PullTaskPullCallParticipantResultService service;

    @BeforeEach
    void setUp() {
        callMapper = mock(PullTaskPullCallMapper.class);
        attemptMapper = mock(PullTaskPullCallMemberAttemptMapper.class);
        materialMapper = mock(PullTaskMaterialMemberMapper.class);
        accountMapper = mock(PullTaskGroupAccountMapper.class);
        executionMapper = mock(PullTaskGroupExecutionMapper.class);
        settingMapper = mock(PullTaskStandardSettingMapper.class);
        delayPolicy = mock(PullTaskOperationDelayPolicy.class);
        service = new PullTaskPullCallParticipantResultService(
                new PullTaskUnknownResultResources(
                        mock(PullTaskAccountActionMapper.class), callMapper, attemptMapper,
                        materialMapper, accountMapper),
                executionMapper, settingMapper, delayPolicy);
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
    void lastTerminalAttemptClosesCallAndWakesExecutionOnce() {
        PullTaskPullCall call = call();
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
        when(materialMapper.selectPendingAdmin(any(Long.class), any(Integer.class),
                any(Integer.class), any(Integer.class))).thenReturn(List.of());
        when(materialMapper.selectUnconsumed(21L, 1)).thenReturn(List.of());
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        ArgumentCaptor<PullTaskFactTransition> callChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(callMapper).transitionResult(callChange.capture());
        assertThat(callChange.getValue().targetStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.CLOSING.code());
        assertThat(executionChange.getValue().nextPullerIndex()).isNull();
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
    void lateSuccessCancelsWholeNewerPlannedCallBeforePromotion() {
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
        PullTaskGroupAccount station = aggregateStation(52L, 43L);
        PullTaskPullCall newerCall = call();
        newerCall.setId(32L);
        newerCall.setCallStatus(PullTaskPullCallStatus.PLANNED.code());
        when(materialMapper.selectByExecution(21L)).thenReturn(List.of(material));
        when(accountMapper.selectById(52L)).thenReturn(station);
        when(attemptMapper.selectById(42L)).thenReturn(newerMaterial);
        when(attemptMapper.selectByCall(32L))
                .thenReturn(List.of(newerMaterial, newerStation));
        when(callMapper.selectByExecution(21L)).thenReturn(List.of(call(), newerCall));
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);
        when(accountMapper.transitionMembershipAttempt(any())).thenReturn(1);
        when(materialMapper.promotePullSuccess(any())).thenReturn(1);
        when(callMapper.transitionResult(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        ArgumentCaptor<PullTaskParticipantAttemptTransition> attempts =
                ArgumentCaptor.forClass(PullTaskParticipantAttemptTransition.class);
        verify(attemptMapper, org.mockito.Mockito.times(3)).transition(attempts.capture());
        assertThat(attempts.getAllValues())
                .extracting(row -> row.target().lifecycleStatus())
                .containsExactly(
                        PullTaskParticipantAttemptStatus.CLOSED.code(),
                        PullTaskParticipantAttemptStatus.CANCELED.code(),
                        PullTaskParticipantAttemptStatus.CANCELED.code());
        ArgumentCaptor<PullTaskFactTransition> callChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(callMapper).transitionResult(callChange.capture());
        assertThat(callChange.getValue().expectedStatuses())
                .containsExactly(PullTaskPullCallStatus.PLANNED.code());
        assertThat(callChange.getValue().targetStatus())
                .isEqualTo(PullTaskPullCallStatus.CANCELED.code());
        verify(materialMapper).promotePullSuccess(any());
    }

    @Test
    void rosterPresenceConfirmsUncertainAttemptAsSuccess() {
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 2L,
                PullTaskParticipantAttemptStatus.SUBMITTED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);

        assertThat(service.settleUncertain(
                7L, call(), execution(), attempt, true, 6_000L)).isTrue();

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

        assertThat(service.settleUncertain(
                7L, call(), execution(), attempt, false, 6_000L)).isTrue();

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
    void bulkCallSamplesSilenceOnceOnlyWhenWinningClosureCanRunAnotherSideEffect() {
        PullTaskPullCall call = call();
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(0);
        call.setSubmittedAt(1_000L);
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.MATERIAL, 0L,
                PullTaskParticipantAttemptStatus.SUBMITTED, null, null);
        PullTaskPullCallMemberAttempt closed = copyAttempt(
                attempt, PullTaskParticipantAttemptStatus.CLOSED);
        closed.setExecutionState(PullTaskParticipantExecutionState.STARTED);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(materialMapper.transitionPullAttempt(any())).thenReturn(1);
        when(attemptMapper.selectByCall(31L)).thenReturn(List.of(closed));
        when(callMapper.transitionResult(any())).thenReturn(1);
        when(materialMapper.selectPendingAdmin(any(Long.class), any(Integer.class),
                any(Integer.class), any(Integer.class))).thenReturn(List.of());
        when(materialMapper.selectUnconsumed(21L, 1))
                .thenReturn(List.of(aggregateMaterial(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, null)));
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setPullIntervalSeconds(2);
        when(settingMapper.selectByTaskId(11L)).thenReturn(setting);
        when(delayPolicy.maxDeadline(3_000L, 5_000L)).thenReturn(9_000L);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        assertThat(service.handle(callback(
                PullTaskBatchParticipantProtocolOutcome.SUCCESS,
                PullTaskParticipantExecutionState.STARTED, false))).isTrue();

        verify(delayPolicy).maxDeadline(3_000L, 5_000L);
        ArgumentCaptor<PullTaskExecutionResultTransition> change =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(change.capture());
        assertThat(change.getValue().nextRunAt()).isEqualTo(9_000L);
    }

    @Test
    void rosterOnlyClosureDoesNotSampleSideEffectSilence() {
        PullTaskPullCall call = call();
        call.setPlannedMaterialCount(0);
        call.setPlannedStationCount(1);
        PullTaskPullCallMemberAttempt attempt = stubAttempt(
                PullTaskParticipantType.STATION, 0L,
                PullTaskParticipantAttemptStatus.SUBMITTED,
                "UNKNOWN", PullTaskParticipantExecutionState.UNCERTAIN);
        PullTaskPullCallMemberAttempt released = copyAttempt(
                attempt, PullTaskParticipantAttemptStatus.RELEASED);
        released.setExecutionState(PullTaskParticipantExecutionState.UNCERTAIN);
        when(attemptMapper.transition(any())).thenReturn(1);
        when(accountMapper.transitionMembershipAttempt(any())).thenReturn(1);
        when(attemptMapper.selectByCall(31L)).thenReturn(List.of(released));
        when(callMapper.transitionResult(any())).thenReturn(1);
        when(materialMapper.selectPendingAdmin(any(Long.class), any(Integer.class),
                any(Integer.class), any(Integer.class))).thenReturn(List.of());
        when(materialMapper.selectUnconsumed(21L, 1))
                .thenReturn(List.of(aggregateMaterial(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, null)));
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setPullIntervalSeconds(0);
        when(settingMapper.selectByTaskId(11L)).thenReturn(setting);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        assertThat(service.settleUncertain(
                7L, call, execution(), attempt, false, 6_000L)).isTrue();

        verify(delayPolicy, never()).nextSideEffectAt(anyLong());
        verify(delayPolicy, never()).maxDeadline(anyLong(), anyLong());
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
        call.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        call.setCommandId("cmd-call");
        return call;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(21L);
        execution.setTaskId(11L);
        execution.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        execution.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        execution.setVersion(3);
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
        return new PullTaskBatchParticipantCallback(
                7L, 11L, 21L, 31L, 71L, "protocol-71", "cmd-call", 1,
                TARGET, outcome, executionState, null, null, retryable, 5_000L);
    }
}
