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
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskUnknownResultReconciliationServiceTest {

    private static final long NOW = 50_000L;
    private static final long CUTOFF = 40_000L;

    private PullTaskAccountActionMapper actionMapper;
    private PullTaskPullCallMapper callMapper;
    private PullTaskMaterialMemberMapper materialMapper;
    private PullTaskGroupAccountMapper accountMapper;
    private PullTaskGroupExecutionMapper executionMapper;
    private AccountProtocolLookupService accountLookup;
    private GroupMemberListPort memberListPort;
    private PullTaskUnknownResultReconciliationService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(PullTaskAccountActionMapper.class);
        callMapper = mock(PullTaskPullCallMapper.class);
        materialMapper = mock(PullTaskMaterialMemberMapper.class);
        accountMapper = mock(PullTaskGroupAccountMapper.class);
        executionMapper = mock(PullTaskGroupExecutionMapper.class);
        accountLookup = mock(AccountProtocolLookupService.class);
        memberListPort = mock(GroupMemberListPort.class);
        service = new PullTaskUnknownResultReconciliationService(
                new PullTaskUnknownResultResources(
                        actionMapper, callMapper,
                        mock(PullTaskPullCallMemberAttemptMapper.class),
                        materialMapper, accountMapper),
                accountLookup, memberListPort, executionMapper,
                mock(PullTaskPullCallReconciliationService.class));
    }

    @Test
    void unknownMaterialConvergesFromMemberSnapshotWithoutReissuingCommand() {
        PullTaskGroupExecution execution = execution("123@g.us");
        PullTaskGroupAccount manager = account(11L, 101L, "8613800000001",
                new AccountState(PullTaskGroupAccountRole.MANAGER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        PullTaskGroupAccount puller = account(12L, 102L, "8613800000002",
                new AccountState(PullTaskGroupAccountRole.PULLER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        puller.setRoleSeq(2);
        PullTaskMaterialMember material = material(21L, 31L, "8613800000099",
                PullTaskMaterialPullStatus.UNKNOWN.code());
        PullTaskPullCall call = call(31L, PullTaskPullCallStatus.SUBMITTED.code(), 20_000L);
        call.setPullerGroupAccountId(puller.getId());
        stubRows(execution.getId(), List.of(manager, puller), List.of(material), List.of(call));
        when(accountLookup.findActiveProtocolRefs(List.of(101L, 102L)))
                .thenReturn(List.of(protocol(101L, manager.getAccountPhone())));
        when(memberListPort.list(any())).thenReturn(List.of(
                new GroupParticipantResult("8613800000099@s.whatsapp.net",
                        "8613800000099", false, false, null)));
        when(materialMapper.transitionPullResult(any())).thenReturn(1);
        when(materialMapper.countByPullCallAndStatuses(any())).thenReturn(0);
        when(accountMapper.countByPullCallAndMembershipStatuses(any())).thenReturn(0);
        when(callMapper.transitionResult(any())).thenReturn(1);
        when(executionMapper.transitionProtocolResult(any())).thenReturn(1);

        PullTaskUnknownResultReconciliationStats stats =
                service.reconcile(execution, CUTOFF, NOW);

        ArgumentCaptor<PullTaskFactTransition> materialChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(materialMapper).transitionPullResult(materialChange.capture());
        assertThat(materialChange.getValue().targetStatus())
                .isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        ArgumentCaptor<PullTaskFactTransition> callChange =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(callMapper).transitionResult(callChange.capture());
        assertThat(callChange.getValue().targetStatus())
                .isEqualTo(PullTaskPullCallStatus.WRITTEN_BACK.code());
        ArgumentCaptor<PullTaskExecutionResultTransition> executionChange =
                ArgumentCaptor.forClass(PullTaskExecutionResultTransition.class);
        verify(executionMapper).transitionProtocolResult(executionChange.capture());
        assertThat(executionChange.getValue().nextPullerIndex()).isEqualTo(3);
        assertThat(executionChange.getValue().targetStage())
                .isEqualTo(PullTaskExecutionStage.PULL_EXECUTION.code());
        assertThat(stats.confirmed()).isEqualTo(2);
    }

    @Test
    void staleSubmittedContactBecomesUnknownWhenMembershipCannotProveIt() {
        PullTaskGroupExecution execution = execution(null);
        PullTaskAccountAction action = action(
                41L, PullTaskAccountActionType.SAVE_CONTACT.code(),
                PullTaskActionStatus.SUBMITTED.code(), 20_000L);
        when(actionMapper.selectByExecutionAndStatuses(
                execution.getId(), List.of(
                        PullTaskActionStatus.SUBMITTED.code(),
                        PullTaskActionStatus.UNKNOWN.code())))
                .thenReturn(List.of(action));
        when(callMapper.selectByExecution(execution.getId())).thenReturn(List.of());
        when(materialMapper.selectByExecution(execution.getId())).thenReturn(List.of());
        stubAccounts(execution.getId(), List.of());
        when(actionMapper.transitionResult(any())).thenReturn(1);

        PullTaskUnknownResultReconciliationStats stats =
                service.reconcile(execution, CUTOFF, NOW);

        ArgumentCaptor<PullTaskFactTransition> change =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(change.capture());
        assertThat(change.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        verify(memberListPort, never()).list(any());
        assertThat(stats.markedUnknown()).isOne();
    }

    @Test
    void unknownMaterialAdminConvergesOnlyWhenSnapshotConfirmsPermission() {
        PullTaskGroupExecution execution = execution("123@g.us");
        PullTaskGroupAccount manager = account(11L, 101L, "8613800000001",
                new AccountState(PullTaskGroupAccountRole.MANAGER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        PullTaskMaterialMember material = material(
                21L, 31L, "8613800000099", PullTaskMaterialPullStatus.SUCCESS.code());
        material.setWaJid("8613800000099@s.whatsapp.net");
        material.setAdminStatus(PullTaskMaterialAdminStatus.UNKNOWN.code());
        stubRows(execution.getId(), List.of(manager), List.of(material), List.of());
        when(accountLookup.findActiveProtocolRefs(List.of(101L)))
                .thenReturn(List.of(protocol(101L, manager.getAccountPhone())));
        when(memberListPort.list(any())).thenReturn(List.of(
                new GroupParticipantResult(material.getWaJid(), material.getNormalizedPhone(),
                        true, false, "admin")));
        when(materialMapper.transitionAdminResult(any())).thenReturn(1);

        PullTaskUnknownResultReconciliationStats stats =
                service.reconcile(execution, CUTOFF, NOW);

        ArgumentCaptor<PullTaskFactTransition> change =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(materialMapper).transitionAdminResult(change.capture());
        assertThat(change.getValue().targetStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
        assertThat(stats.confirmed()).isOne();
    }

    @Test
    void submittedManagerPromotionDoesNotSucceedWhenTargetIsOnlyOrdinaryMember() {
        PullTaskGroupExecution execution = execution("123@g.us");
        PullTaskGroupAccount manager = account(11L, 101L, "8613800000001",
                new AccountState(PullTaskGroupAccountRole.MANAGER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        manager.setAdminStatus(PullTaskGroupAccountAdminStatus.SUBMITTED.code());
        manager.setUpdatedAt(20_000L);
        PullTaskGroupAccount promoter = account(12L, 102L, "8613800000002",
                new AccountState(PullTaskGroupAccountRole.PROMOTER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        PullTaskAccountAction action = action(
                41L, PullTaskAccountActionType.PROMOTE_MANAGER.code(),
                PullTaskActionStatus.SUBMITTED.code(), 20_000L);
        action.setActorGroupAccountId(promoter.getId());
        action.setTargetGroupAccountId(manager.getId());
        stubRows(execution.getId(), List.of(manager, promoter), List.of(), List.of());
        when(actionMapper.selectByExecutionAndStatuses(execution.getId(), List.of(
                PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code())))
                .thenReturn(List.of(action));
        when(accountLookup.findActiveProtocolRefs(List.of(101L, 102L)))
                .thenReturn(List.of(protocol(102L, promoter.getAccountPhone())));
        when(memberListPort.list(any())).thenReturn(List.of(
                new GroupParticipantResult(
                        "8613800000001@s.whatsapp.net", "8613800000001",
                        false, false, null),
                new GroupParticipantResult(
                        "8613800000002@s.whatsapp.net", "8613800000002",
                        true, false, "admin")));
        when(actionMapper.transitionManagerAdminObservation(
                anyLong(), any(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyLong()))
                .thenReturn(1);
        when(accountMapper.transitionAdminStatus(anyLong(), any(), anyInt(), anyLong()))
                .thenReturn(1);

        service.reconcile(execution, CUTOFF, NOW);

        verify(actionMapper).transitionManagerAdminObservation(
                action.getId(), List.of(PullTaskActionStatus.SUBMITTED.code()),
                PullTaskActionStatus.UNKNOWN.code(), true,
                "MANAGER_ADMIN_UNCONFIRMED", "管理员权限结果暂未确认", NOW);
        verify(accountMapper).transitionAdminStatus(
                manager.getId(), List.of(PullTaskGroupAccountAdminStatus.SUBMITTED.code()),
                PullTaskGroupAccountAdminStatus.UNKNOWN.code(), NOW);
    }

    @Test
    void submittedManagerPromotionSucceedsOnlyWhenTargetIsAdmin() {
        PullTaskGroupExecution execution = execution("123@g.us");
        PullTaskGroupAccount manager = account(11L, 101L, "8613800000001",
                new AccountState(PullTaskGroupAccountRole.MANAGER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        manager.setAdminStatus(PullTaskGroupAccountAdminStatus.SUBMITTED.code());
        PullTaskGroupAccount promoter = account(12L, 102L, "8613800000002",
                new AccountState(PullTaskGroupAccountRole.PROMOTER.code(), null,
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()));
        PullTaskAccountAction action = action(
                41L, PullTaskAccountActionType.PROMOTE_MANAGER.code(),
                PullTaskActionStatus.SUBMITTED.code(), 20_000L);
        action.setActorGroupAccountId(promoter.getId());
        action.setTargetGroupAccountId(manager.getId());
        stubRows(execution.getId(), List.of(manager, promoter), List.of(), List.of());
        when(actionMapper.selectByExecutionAndStatuses(execution.getId(), List.of(
                PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code())))
                .thenReturn(List.of(action));
        when(accountLookup.findActiveProtocolRefs(List.of(101L, 102L)))
                .thenReturn(List.of(protocol(102L, promoter.getAccountPhone())));
        when(memberListPort.list(any())).thenReturn(List.of(
                new GroupParticipantResult(
                        "8613800000001@s.whatsapp.net", "8613800000001",
                        true, false, "admin")));
        when(actionMapper.transitionManagerAdminObservation(
                anyLong(), any(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), anyLong()))
                .thenReturn(1);
        when(accountMapper.transitionAdminStatus(anyLong(), any(), anyInt(), anyLong()))
                .thenReturn(1);

        service.reconcile(execution, CUTOFF, NOW);

        verify(actionMapper).transitionManagerAdminObservation(
                action.getId(), List.of(
                        PullTaskActionStatus.SUBMITTED.code(),
                        PullTaskActionStatus.UNKNOWN.code()),
                PullTaskActionStatus.SUCCESS.code(), false, null, null, NOW);
        verify(accountMapper).transitionAdminStatus(
                manager.getId(), List.of(
                        PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
                        PullTaskGroupAccountAdminStatus.UNKNOWN.code()),
                PullTaskGroupAccountAdminStatus.SUCCESS.code(), NOW);
    }

    private void stubRows(
            long executionId,
            List<PullTaskGroupAccount> accounts,
            List<PullTaskMaterialMember> materials,
            List<PullTaskPullCall> calls) {
        when(actionMapper.selectByExecutionAndStatuses(
                executionId, List.of(
                        PullTaskActionStatus.SUBMITTED.code(),
                        PullTaskActionStatus.UNKNOWN.code())))
                .thenReturn(List.of());
        when(callMapper.selectByExecution(executionId)).thenReturn(calls);
        when(materialMapper.selectByExecution(executionId)).thenReturn(materials);
        stubAccounts(executionId, accounts);
    }

    private void stubAccounts(long executionId, List<PullTaskGroupAccount> accounts) {
        for (PullTaskGroupAccountRole role : PullTaskGroupAccountRole.values()) {
            when(accountMapper.selectByExecutionAndRole(executionId, role.code()))
                    .thenReturn(accounts.stream()
                            .filter(row -> row.getRoleType() == role.code()).toList());
        }
    }

    private static PullTaskGroupExecution execution(String groupJid) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(1L);
        row.setTenantId(7L);
        row.setTaskId(2L);
        row.setGroupJid(groupJid);
        row.setVersion(4);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        return row;
    }

    private static PullTaskGroupAccount account(
            long id, long accountId, String phone, AccountState state) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(state.role());
        row.setPullCallId(state.callId());
        row.setMembershipStatus(state.membership());
        return row;
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

    private static PullTaskPullCall call(long id, int status, long submittedAt) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setId(id);
        row.setCallStatus(status);
        row.setSubmittedAt(submittedAt);
        return row;
    }

    private static PullTaskAccountAction action(
            long id, int type, int status, long submittedAt) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(id);
        row.setActionType(type);
        row.setActionStatus(status);
        row.setSubmittedAt(submittedAt);
        return row;
    }

    private static ProtocolAccountRef protocol(long accountId, String phone) {
        return new ProtocolAccountRef(
                accountId, ProtocolBackend.WEB, "protocol-" + accountId, phone);
    }

    private record AccountState(int role, Long callId, int membership) {
    }
}
