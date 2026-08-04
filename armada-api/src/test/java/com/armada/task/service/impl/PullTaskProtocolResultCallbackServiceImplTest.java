package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskCallReschedule;
import com.armada.task.model.dto.PullTaskCommandCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskProtocolResultCallbackServiceImplTest {

    private PullTaskAccountActionMapper actionMapper;
    private PullTaskPullCallMapper callMapper;
    private PullTaskMaterialMemberMapper materialMapper;
    private PullTaskGroupAccountMapper accountMapper;
    private PullTaskGroupExecutionMapper executionMapper;
    private PullTaskStandardSettingMapper settingMapper;
    private PullTaskProtocolResultCallbackServiceImpl service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(PullTaskAccountActionMapper.class);
        callMapper = mock(PullTaskPullCallMapper.class);
        materialMapper = mock(PullTaskMaterialMemberMapper.class);
        accountMapper = mock(PullTaskGroupAccountMapper.class);
        executionMapper = mock(PullTaskGroupExecutionMapper.class);
        settingMapper = mock(PullTaskStandardSettingMapper.class);
        service = new PullTaskProtocolResultCallbackServiceImpl(
                new PullTaskUnknownResultResources(
                        actionMapper, callMapper, materialMapper, accountMapper),
                executionMapper, settingMapper);
    }

    @Test
    void actionCallbackConvergesUnknownAndMembershipWithCas() {
        PullTaskAccountAction action = new PullTaskAccountAction();
        action.setId(11L);
        action.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
        action.setActionStatus(PullTaskActionStatus.UNKNOWN.code());
        action.setTargetGroupAccountId(21L);
        PullTaskGroupAccount target = account(
                21L, PullTaskGroupAccountRole.PULLER, null,
                PullTaskGroupAccountMembershipStatus.UNKNOWN.code());
        when(actionMapper.selectByCommandId("cmd-action")).thenReturn(action);
        when(accountMapper.selectById(21L)).thenReturn(target);
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);

        boolean handled = service.handleAccountAction(new PullTaskCommandCallback(
                "cmd-action", PullTaskProtocolOutcome.SUCCESS,
                null, null, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> actionChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(actionChange.capture());
        assertThat(actionChange.getValue().expectedStatuses()).containsExactly(
                PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());
        assertThat(actionChange.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        ArgumentCaptor<PullTaskFactTransition> membershipChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(accountMapper).transitionMembership(membershipChange.capture());
        assertThat(membershipChange.getValue().targetStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
    }

    @Test
    void pullCallParticipantCallbackWritesOnlyReportedTargetWhileOthersArePending() {
        PullTaskPullCall call = submittedCall();
        PullTaskMaterialMember confirmed = material(
                41L, 31L, "8613800000001", PullTaskMaterialPullStatus.SUBMITTED.code());
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(execution());
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of(confirmed));
        stubAccounts(1L, List.of(puller()));
        when(materialMapper.transitionPullResult(any())).thenReturn(1);
        when(materialMapper.countByPullCallAndStatuses(any())).thenReturn(1);
        when(accountMapper.countByPullCallAndMembershipStatuses(any())).thenReturn(0);

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000001@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> materialChanges =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(materialMapper).transitionPullResult(materialChanges.capture());
        assertThat(materialChanges.getValue().targetStatus())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        verify(callMapper, never()).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void offlinePullerReschedulesFrozenCallWithoutWritingParticipantFailure() {
        PullTaskPullCall call = submittedCall();
        PullTaskMaterialMember material = material(
                41L, 31L, "8613800000001", PullTaskMaterialPullStatus.SUBMITTED.code());
        PullTaskGroupAccount puller = puller();
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(execution());
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of(material));
        stubAccounts(1L, List.of(puller));
        when(callMapper.rescheduleSubmitted(any())).thenReturn(1);
        when(accountMapper.markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                "ACCOUNT_NOT_ONLINE", null, 5_000L)).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000001@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.FAILED,
                "ACCOUNT_NOT_ONLINE"));

        assertThat(handled).isTrue();
        verify(materialMapper, never()).transitionPullResult(any());
        verify(accountMapper, never()).transitionMembership(any());
        ArgumentCaptor<PullTaskCallReschedule> callChange =
                ArgumentCaptor.forClass(PullTaskCallReschedule.class);
        verify(callMapper).rescheduleSubmitted(callChange.capture());
        assertThat(callChange.getValue().status().target())
                .isEqualTo(PullTaskPullCallStatus.PLANNED.code());
        assertThat(callChange.getValue().scope().expectedCommandId()).isEqualTo("cmd-call");
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(executionChange.getValue().nextRunAt()).isZero();
    }

    @Test
    void lastPullCallParticipantFinalizesCallAndAdvancesExecutionWithCas() {
        PullTaskPullCall call = submittedCall();
        PullTaskGroupAccount station = account(
                51L, PullTaskGroupAccountRole.STATION, 31L,
                PullTaskGroupAccountMembershipStatus.JOINING.code());
        station.setAccountPhone("8613800000002");
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(execution());
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of());
        stubAccounts(1L, List.of(puller(), station));
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(materialMapper.countByPullCallAndStatuses(any())).thenReturn(0);
        when(accountMapper.countByPullCallAndMembershipStatuses(any())).thenReturn(0);
        when(callMapper.transitionResult(any())).thenReturn(1);
        when(materialMapper.selectUnconsumed(1L, 1)).thenReturn(List.of());
        when(materialMapper.selectPendingAdmin(any(Long.class), any(Integer.class),
                any(Integer.class), any(Integer.class))).thenReturn(List.of());
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000002@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
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
        assertThat(executionChange.getValue().nextPullerIndex()).isZero();
    }

    @Test
    void lateParticipantCallbackConvergesUnknownCallAfterExecutionAdvanced() {
        PullTaskPullCall call = submittedCall();
        call.setCallStatus(PullTaskPullCallStatus.UNKNOWN.code());
        PullTaskMaterialMember material = material(
                41L, 31L, "8613800000001", PullTaskMaterialPullStatus.UNKNOWN.code());
        PullTaskGroupExecution advanced = execution();
        advanced.setStage(PullTaskExecutionStage.CLOSING.code());
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(advanced);
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of(material));
        stubAccounts(1L, List.of(puller()));
        when(materialMapper.transitionPullResult(any())).thenReturn(1);
        when(materialMapper.countByPullCallAndStatuses(any())).thenReturn(0);
        when(accountMapper.countByPullCallAndMembershipStatuses(any())).thenReturn(0);
        when(callMapper.transitionResult(any())).thenReturn(1);

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000001@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> callChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(callMapper).transitionResult(callChange.capture());
        assertThat(callChange.getValue().expectedStatuses())
                .containsExactly(PullTaskPullCallStatus.UNKNOWN.code());
        assertThat(callChange.getValue().targetStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void duplicateParticipantCallbackRemainsIdempotentAfterCallWrittenBack() {
        PullTaskPullCall call = submittedCall();
        call.setCallStatus(PullTaskPullCallStatus.WRITTEN_BACK.code());
        PullTaskMaterialMember material = material(
                41L, 31L, "8613800000001", PullTaskMaterialPullStatus.SUCCESS.code());
        PullTaskGroupExecution advanced = execution();
        advanced.setStage(PullTaskExecutionStage.CLOSING.code());
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(advanced);
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of(material));
        stubAccounts(1L, List.of(puller()));

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000001@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        verify(materialMapper, never()).transitionPullResult(any());
        verify(callMapper, never()).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void lateUnknownCallDoesNotAdvanceCursorAgainWhenExecutionStageIsStillPulling() {
        PullTaskPullCall call = submittedCall();
        call.setCallStatus(PullTaskPullCallStatus.UNKNOWN.code());
        PullTaskMaterialMember material = material(
                41L, 31L, "8613800000001", PullTaskMaterialPullStatus.UNKNOWN.code());
        when(callMapper.selectByCommandId("cmd-call")).thenReturn(call);
        when(executionMapper.selectById(1L)).thenReturn(execution());
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of(material));
        stubAccounts(1L, List.of(puller()));
        when(materialMapper.transitionPullResult(any())).thenReturn(1);
        when(materialMapper.countByPullCallAndStatuses(any())).thenReturn(0);
        when(accountMapper.countByPullCallAndMembershipStatuses(any())).thenReturn(0);
        when(callMapper.transitionResult(any())).thenReturn(1);

        boolean handled = service.handlePullCallParticipant(callback(
                "8613800000001@s.whatsapp.net",
                PullTaskBatchParticipantProtocolOutcome.SUCCESS));

        assertThat(handled).isTrue();
        verify(callMapper).transitionResult(any());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    @Test
    void materialAdminCallbackConvergesUnknownByAdminCommandId() {
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setId(51L);
        material.setGroupExecutionId(1L);
        material.setAdminRequired(1);
        material.setPullStatus(PullTaskMaterialPullStatus.SUCCESS.code());
        material.setWaJid("8613900000001@s.whatsapp.net");
        material.setAdminStatus(PullTaskMaterialAdminStatus.UNKNOWN.code());
        material.setAdminCommandId("cmd-admin");
        PullTaskGroupAccount manager = manager();
        when(materialMapper.selectByAdminCommandId("cmd-admin")).thenReturn(material);
        when(executionMapper.selectById(1L)).thenReturn(adminExecution());
        stubAccounts(1L, List.of(manager));
        when(materialMapper.transitionAdminResult(any())).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                any(Long.class), any(), any(Integer.class), any(Long.class))).thenReturn(1);
        when(materialMapper.selectByExecution(1L)).thenReturn(List.of());
        when(materialMapper.selectUnconsumed(1L, 1)).thenReturn(List.of());
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                7L, 100L, 1L, 51L, 901L, "manager-901", "cmd-admin", 1,
                "8613900000001@s.whatsapp.net", PullTaskMaterialAdminProtocolOutcome.FAILED,
                "NOT_ALLOWED", "权限不足", false, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> change =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(materialMapper).transitionAdminResult(change.capture());
        assertThat(change.getValue().targetStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.FAILED.code());
        assertThat(change.getValue().result().reasonCode()).isEqualTo("NOT_ALLOWED");
        verify(accountMapper).transitionAdminStatus(
                manager.getId(), List.of(
                        PullTaskGroupAccountAdminStatus.PENDING.code(),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code(),
                        PullTaskGroupAccountAdminStatus.UNKNOWN.code()),
                PullTaskGroupAccountAdminStatus.SUCCESS.code(), 5_000L);
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.CLOSING.code());
    }

    @Test
    void actorDeniedReturnsMaterialToPendingAndRejectsManagerPermission() {
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setId(51L);
        material.setGroupExecutionId(1L);
        material.setAdminRequired(1);
        material.setPullStatus(PullTaskMaterialPullStatus.SUCCESS.code());
        material.setWaJid("8613900000001@s.whatsapp.net");
        material.setAdminStatus(PullTaskMaterialAdminStatus.SUBMITTED.code());
        material.setAdminCommandId("cmd-admin");
        PullTaskGroupAccount manager = manager();
        when(materialMapper.selectByAdminCommandId("cmd-admin")).thenReturn(material);
        when(executionMapper.selectById(1L)).thenReturn(adminExecution());
        stubAccounts(1L, List.of(manager));
        when(materialMapper.returnAdminToPending(
                51L, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.PENDING.code(),
                "MATERIAL_ADMIN_ACTOR_PERMISSION_DENIED", 5_000L)).thenReturn(1);
        when(accountMapper.transitionAdminStatus(
                any(Long.class), any(), any(Integer.class), any(Long.class))).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                7L, 100L, 1L, 51L, 901L, "manager-901", "cmd-admin", 1,
                "8613900000001@s.whatsapp.net", PullTaskMaterialAdminProtocolOutcome.FAILED,
                "MATERIAL_ADMIN_ACTOR_PERMISSION_DENIED", "not admin", false, 5_000L));

        assertThat(handled).isTrue();
        verify(materialMapper, never()).transitionAdminResult(any());
        verify(accountMapper).transitionAdminStatus(
                manager.getId(), List.of(
                        PullTaskGroupAccountAdminStatus.PENDING.code(),
                        PullTaskGroupAccountAdminStatus.SUCCESS.code(),
                        PullTaskGroupAccountAdminStatus.UNKNOWN.code()),
                PullTaskGroupAccountAdminStatus.FAILED.code(), 5_000L);
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.MATERIAL_ADMIN.code());
    }

    @Test
    void accountNotOnlineReturnsMaterialToPendingAndMarksManagerUnavailable() {
        PullTaskMaterialMember material = submittedAdminMaterial();
        PullTaskGroupAccount manager = manager();
        when(materialMapper.selectByAdminCommandId("cmd-admin")).thenReturn(material);
        when(executionMapper.selectById(1L)).thenReturn(adminExecution());
        stubAccounts(1L, List.of(manager));
        when(accountMapper.markUnavailable(
                manager.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                "ACCOUNT_NOT_ONLINE", null, 5_000L)).thenReturn(1);
        when(materialMapper.returnAdminToPending(
                51L, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.PENDING.code(),
                "ACCOUNT_NOT_ONLINE", 5_000L)).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        boolean handled = service.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                7L, 100L, 1L, 51L, 901L, "manager-901", "cmd-admin", 1,
                "8613900000001@s.whatsapp.net", PullTaskMaterialAdminProtocolOutcome.FAILED,
                "ACCOUNT_NOT_ONLINE", "offline", false, 5_000L));

        assertThat(handled).isTrue();
        verify(accountMapper, never()).transitionAdminStatus(
                any(Long.class), any(), any(Integer.class), any(Long.class));
        verify(materialMapper, never()).transitionAdminResult(any());
    }

    @Test
    void lateCallbackConvergesUnknownFactAfterExecutionAdvanced() {
        PullTaskMaterialMember material = submittedAdminMaterial();
        material.setAdminStatus(PullTaskMaterialAdminStatus.UNKNOWN.code());
        PullTaskGroupExecution execution = adminExecution();
        execution.setStage(PullTaskExecutionStage.CLOSING.code());
        PullTaskGroupAccount manager = manager();
        when(materialMapper.selectByAdminCommandId("cmd-admin")).thenReturn(material);
        when(executionMapper.selectById(1L)).thenReturn(execution);
        stubAccounts(1L, List.of(manager));
        when(accountMapper.transitionAdminStatus(
                any(Long.class), any(), any(Integer.class), any(Long.class))).thenReturn(1);
        when(materialMapper.transitionAdminResult(any())).thenReturn(1);

        boolean handled = service.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                7L, 100L, 1L, 51L, 901L, "manager-901", "cmd-admin", 1,
                "8613900000001@s.whatsapp.net", PullTaskMaterialAdminProtocolOutcome.SUCCESS,
                null, null, false, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> change =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(materialMapper).transitionAdminResult(change.capture());
        assertThat(change.getValue().targetStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    private void stubAccounts(long executionId, List<PullTaskGroupAccount> rows) {
        for (PullTaskGroupAccountRole role : PullTaskGroupAccountRole.values()) {
            when(accountMapper.selectByExecutionAndRole(executionId, role.code()))
                    .thenReturn(rows.stream()
                            .filter(row -> row.getRoleType() == role.code()).toList());
        }
    }

    private static PullTaskMaterialMember material(
            long id, long callId, String phone, int status) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setId(id);
        row.setPullCallId(callId);
        row.setNormalizedPhone(phone);
        row.setPullStatus(status);
        return row;
    }

    private static PullTaskGroupAccount account(
            long id, PullTaskGroupAccountRole role, Long callId, int membership) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setRoleType(role.code());
        row.setPullCallId(callId);
        row.setMembershipStatus(membership);
        return row;
    }

    private static PullTaskPullCall submittedCall() {
        PullTaskPullCall call = new PullTaskPullCall();
        call.setId(31L);
        call.setTaskId(100L);
        call.setGroupExecutionId(1L);
        call.setPullerGroupAccountId(71L);
        call.setPullerAccountId(902L);
        call.setCommandId("cmd-call");
        call.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        return call;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(1L);
        execution.setTaskId(100L);
        execution.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        execution.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        execution.setVersion(5);
        return execution;
    }

    private static PullTaskGroupAccount puller() {
        PullTaskGroupAccount puller = account(
                71L, PullTaskGroupAccountRole.PULLER, null,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        puller.setTaskId(100L);
        puller.setGroupExecutionId(1L);
        puller.setAccountId(902L);
        puller.setAvailabilityStatus(1);
        return puller;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount manager = account(
                81L, PullTaskGroupAccountRole.MANAGER, null,
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        manager.setTaskId(100L);
        manager.setGroupExecutionId(1L);
        manager.setAccountId(901L);
        manager.setAdminStatus(PullTaskGroupAccountAdminStatus.PENDING.code());
        return manager;
    }

    private static PullTaskMaterialMember submittedAdminMaterial() {
        PullTaskMaterialMember material = new PullTaskMaterialMember();
        material.setId(51L);
        material.setGroupExecutionId(1L);
        material.setAdminRequired(1);
        material.setPullStatus(PullTaskMaterialPullStatus.SUCCESS.code());
        material.setWaJid("8613900000001@s.whatsapp.net");
        material.setAdminStatus(PullTaskMaterialAdminStatus.SUBMITTED.code());
        material.setAdminCommandId("cmd-admin");
        return material;
    }

    private static PullTaskGroupExecution adminExecution() {
        PullTaskGroupExecution execution = execution();
        execution.setStage(PullTaskExecutionStage.MATERIAL_ADMIN.code());
        return execution;
    }

    private static PullTaskBatchParticipantCallback callback(
            String targetJid,
            PullTaskBatchParticipantProtocolOutcome outcome) {
        return callback(targetJid, outcome, null);
    }

    private static PullTaskBatchParticipantCallback callback(
            String targetJid,
            PullTaskBatchParticipantProtocolOutcome outcome,
            String reasonCode) {
        return new PullTaskBatchParticipantCallback(
                7L, 100L, 1L, 31L, 902L, "puller-902", "cmd-call", 1,
                targetJid, outcome, reasonCode, null, false, 5_000L);
    }
}
