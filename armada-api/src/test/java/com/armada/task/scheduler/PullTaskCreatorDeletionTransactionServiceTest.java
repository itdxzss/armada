package com.armada.task.scheduler;

import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.task.mapper.*;
import com.armada.task.model.entity.*;
import com.armada.task.model.enums.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PullTaskCreatorDeletionTransactionServiceTest {
    private final PullTaskCreatorDeletionMapper deletions=mock(PullTaskCreatorDeletionMapper.class);
    private final PullTaskCreatorDeletionGate gate=mock(PullTaskCreatorDeletionGate.class);
    private final PullTaskMapper tasks=mock(PullTaskMapper.class);
    private final PullTaskGroupExecutionMapper executions=mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskGroupAccountMapper roles=mock(PullTaskGroupAccountMapper.class);
    private final PullTaskAccountActionMapper actions=mock(PullTaskAccountActionMapper.class);
    private final AccountCreatorDeletionService lifecycle=mock(AccountCreatorDeletionService.class);
    private final AccountProtocolLookupService accounts=mock(AccountProtocolLookupService.class);
    private final PullTaskCreatorDeletionTransactionService service=new PullTaskCreatorDeletionTransactionService(deletions,gate,
            new PullTaskCreatorDeletionResources(tasks,executions,roles,actions,lifecycle,accounts,new ObjectMapper()));

    @Test void selectionFreezesRealCreatorAndProtocolSafeStableOperation() {
        var execution=new PullTaskGroupExecution();execution.setId(3L);execution.setTaskId(2L);execution.setTenantId(1L);
        var creator=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"100","100");
        when(lifecycle.reserve(any())).thenReturn(true);when(lifecycle.identityHash(creator)).thenReturn("hash");
        when(deletions.insert(any())).thenReturn(1);
        assertThat(service.reserve(execution,creator,"create",1000)).isTrue();
        var frozen=ArgumentCaptor.forClass(PullTaskCreatorDeletion.class);
        verify(deletions).insert(frozen.capture());
        assertThat(frozen.getValue().getCreatorAccountId()).isEqualTo(7L);
        assertThat(frozen.getValue().getCreateOperationId()).isEqualTo("create");
        assertThat(frozen.getValue().getOperationId()).matches("^[a-zA-Z0-9][a-zA-Z0-9_-]{15,95}$");
    }

    @Test void decoratedPhoneFreezesAsDigitsWithoutChangingAccountHashOrCreationBinding() {
        var execution=new PullTaskGroupExecution();execution.setId(3L);execution.setTaskId(2L);
        execution.setTenantId(1L);execution.setCreateOperationId("create");
        var decorated=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"route-7","+12 345 678");
        var canonical=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"route-7","12345678");
        var different=new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"route-7","12345679");
        when(lifecycle.reserve(any())).thenReturn(true);
        when(lifecycle.identityHash(decorated)).thenReturn("unchanged-hash");
        when(lifecycle.identityHash(canonical)).thenReturn("unchanged-hash");
        when(deletions.insert(any())).thenReturn(1);
        assertThat(service.reserve(execution,decorated,"create",1000)).isTrue();
        var frozen=ArgumentCaptor.forClass(PullTaskCreatorDeletion.class);
        verify(deletions).insert(frozen.capture());
        var row=frozen.getValue();
        assertThat(row.getCreatorPhone()).isEqualTo("12345678");
        assertThat(row.getCreatorIdentityHash()).isEqualTo("unchanged-hash");
        assertThat(row.getCreatorAccountId()).isEqualTo(7L);
        assertThat(row.getCreateOperationId()).isEqualTo("create");
        when(deletions.selectByExecutionId(3)).thenReturn(row);
        assertThat(service.frozenCreatorMatches(execution,decorated)).isTrue();
        assertThat(service.frozenCreatorMatches(execution,canonical)).isTrue();
        assertThat(service.frozenCreatorMatches(execution,different)).isFalse();
    }

    @Test void rejectedResultShowsSafeSpecificReasonButNeverRawAuthorizationOrPhone() {
        var work=fixture();
        service.record(work,new CreatorDeletionResult("op","hash","REJECTED","iq","error","server_rejected"),null,1000);
        assertThat(work.deletion().getReasonMessage()).contains("WhatsApp 拒绝注销请求", "SERVER_REJECTED");
        assertThat(work.deletion().getStatus()).isEqualTo(PullTaskCreatorDeletionStatus.FAILED.code());
        var other=fixture();
        service.record(other,new CreatorDeletionResult("op","hash","NOT_SENT","iq","error","authorization=secret phone=12345678"),null,1000);
        assertThat(other.deletion().getReasonMessage()).contains("DETAIL_REDACTED")
                .doesNotContain("secret", "12345678", "authorization");
    }

    @Test void acceptedButCreatorStillPresentDefersAtVerificationStage() {
        var work=fixture();
        service.record(work,accepted(),proof(true),1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionCreatorDeletion(update.capture(),eq(12));
        assertThat(update.getValue().getStage()).isEqualTo(12);
        assertThat(update.getValue().getNextRunAt()).isGreaterThan(1000);
        assertThat(update.getValue().getManualPaused()).isZero();
        verify(lifecycle,never()).completeDeletion(any(),anyLong());
    }
    @Test void allIndependentEvidenceIsRequiredAndCompletionSurvivesExpiredDeadline() {
        var work=fixture();work.deletion().setDeadlineAt(900L);
        service.record(work,accepted(),proof(false),1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionCreatorDeletion(update.capture(),eq(12));
        assertThat(update.getValue().getStage()).isEqualTo(4);
        assertThat(update.getValue().getManualPaused()).isZero();
        verify(lifecycle).completeDeletion(argThat(binding->binding.accountId()==7L && binding.operationId().equals("op")),eq(1000L));
    }
    @Test void changedManagerDuringLiveQueryPausesDespiteOtherwiseCompleteProof() {
        var work=fixture();
        var replacement=new PullTaskGroupAccount();replacement.setAccountId(9L);
        replacement.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        replacement.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        when(roles.selectByExecutionAndRole(3,PullTaskGroupAccountRole.MANAGER.code())).thenReturn(List.of(replacement));
        service.record(work,accepted(),proof(false),1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionCreatorDeletion(update.capture(),eq(12));
        assertThat(update.getValue().getStage()).isEqualTo(12);
        assertThat(update.getValue().getManualPaused()).isEqualTo(1);
        assertThat(update.getValue().getReasonCode()).isEqualTo("CREATOR_DELETE_MANAGER_CHANGED");
        verify(lifecycle,never()).completeDeletion(any(),anyLong());
    }
    @Test void timeoutUnknownPausesWithoutCreatingAnyNewOperation() {
        var work=fixture();work.deletion().setDeadlineAt(999L);
        service.record(work,null,null,1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionCreatorDeletion(update.capture(),eq(12));
        assertThat(update.getValue().getManualPaused()).isEqualTo(1);
        assertThat(update.getValue().getReasonCode()).isEqualTo("CREATOR_DELETE_VERIFICATION_TIMEOUT");
        verify(deletions,never()).claimSubmission(any());
        verify(lifecycle,never()).beginDeletion(any(),anyLong());
    }
    @Test void unknownResultPausesImmediatelyBeforeDeadlineAndPreservesOriginalOperation() {
        var work=fixture();
        service.record(work,new CreatorDeletionResult("op","hash","UNKNOWN",null,null,"timeout"),null,1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionCreatorDeletion(update.capture(),eq(12));
        assertThat(update.getValue().getManualPaused()).isEqualTo(1);
        assertThat(update.getValue().getReasonCode()).isEqualTo("CREATOR_DELETE_RESULT_UNKNOWN");
        assertThat(update.getValue().getReasonMessage()).contains("已暂停", "恢复后仅查询原操作");
        assertThat(work.deletion().getOperationId()).isEqualTo("op");
        verify(deletions,never()).claimSubmission(any());
        verify(lifecycle,never()).beginDeletion(any(),anyLong());
    }
    @Test void stoppedTaskRejectsLateCallbackAndKeepsNoNewCommands() {
        var work=fixture();var parent=new PullTask();parent.setCreationMode(PullTaskCreationMode.NEW_GROUP);parent.setStatus("ENDED");
        when(tasks.selectLifecycleForUpdate(2)).thenReturn(parent);
        assertThat(service.record(work,accepted(),proof(false),1000)).isEqualTo(PullTaskExecutionDispatchResult.LOST);
        verify(deletions,never()).updateObservation(any());
        verifyNoInteractions(lifecycle);
    }
    @Test void alreadySubmittedNeverClaimsDeletionAgainAfterRestart() {
        var work=fixture();
        assertThat(service.claimSubmission(work,proof(true),1000)).isFalse();
        verify(lifecycle,never()).beginDeletion(any(),anyLong());
        verify(deletions,never()).claimSubmission(any());
    }
    private PullTaskCreatorDeletionWork fixture() {
        var parent=new PullTask();parent.setCreationMode(PullTaskCreationMode.NEW_GROUP);parent.setStatus("EXECUTING");
        when(tasks.selectLifecycleForUpdate(2)).thenReturn(parent);
        var execution=new PullTaskGroupExecution();execution.setId(3L);execution.setTaskId(2L);execution.setTenantId(1L);
        execution.setStage(12);execution.setVersion(1);execution.setManualPaused(0);execution.setExecutionStatus(2);
        execution.setLockOwner("lease");execution.setLockExpiresAt(5000L);execution.setGroupJid("g@g.us");
        when(executions.selectByIdForUpdate(3)).thenReturn(execution);
        when(executions.transitionCreatorDeletion(any(),anyInt())).thenReturn(1);
        var row=new PullTaskCreatorDeletion();row.setId(4L);row.setTenantId(1L);row.setTaskId(2L);row.setGroupExecutionId(3L);
        row.setCreatorAccountId(7L);row.setCreatorIdentityHash("hash");row.setOperationId("op");row.setCreateOperationId("create");
        row.setManagerAccountId(8L);
        row.setStatus(1);row.setAttempts(0);row.setCreationBefore(12L);row.setDeadlineAt(10000L);
        when(deletions.selectByExecutionIdForUpdate(3)).thenReturn(row);when(deletions.updateObservation(any())).thenReturn(1);
        var manager=new ProtocolAccountRef(8L,ProtocolBackend.ANDROID,"200","200");
        var role=new PullTaskGroupAccount();role.setAccountId(8L);
        role.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        role.setMembershipStatus(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        when(roles.selectByExecutionAndRole(3,PullTaskGroupAccountRole.MANAGER.code())).thenReturn(List.of(role));
        when(accounts.findEligibleManagerProtocolRefs(List.of(8L))).thenReturn(List.of(manager));
        return new PullTaskCreatorDeletionWork(execution,row,new ProtocolAccountRef(7L,ProtocolBackend.ANDROID,"100","100"),manager);
    }
    private static CreatorDeletionResult accepted() { return new CreatorDeletionResult("op","hash","ACCEPTED","iq","result",null); }
    private static CreatorDeletionObservation proof(boolean present) {
        return new CreatorDeletionObservation("g@g.us",12,present?"100@s.whatsapp.net":"",present?"100":"",true,true,present,true,present,true,1000);
    }
}
