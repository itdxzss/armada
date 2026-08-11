package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskManagerAdminProcessorTest {

    private final PullTaskManagerAdminTransactionService transactions =
            mock(PullTaskManagerAdminTransactionService.class);
    private final PullTaskMemberQueryAwaitService memberQueryAwaitService =
            mock(PullTaskMemberQueryAwaitService.class);
    private final PullTaskManagerAdminProcessor processor =
            new PullTaskManagerAdminProcessor(transactions, memberQueryAwaitService);

    @Test
    void unknownResultUsesMemberQueryToConfirmManagerAdmin() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        PullTaskManagerAdminWork work = work(PullTaskActionStatus.UNKNOWN, true);
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.ready(work));
        queryReturns(member("906", true), member("15", true));
        when(transactions.confirmManagerAdmin(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(transactions).confirmManagerAdmin(work, 1_000L);
        verify(transactions, never()).submitOrDefer(work, 1_000L);
    }

    @Test
    void submittedWithoutCallbackUsesMemberQueryToRejectInvalidPromoter() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        PullTaskManagerAdminWork work = work(PullTaskActionStatus.SUBMITTED, false);
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.ready(work));
        queryReturns(member("906", false), member("15", false));
        when(transactions.rejectPromoter(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions).rejectPromoter(work, 1_000L);
        verify(transactions, never()).submitOrDefer(work, 1_000L);
    }

    @Test
    void pendingActionSubmitsWithoutMemberQuery() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        PullTaskManagerAdminWork work = work(PullTaskActionStatus.PENDING, false);
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.ready(work));
        when(transactions.submitOrDefer(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions).submitOrDefer(work, 1_000L);
        verifyNoInteractions(memberQueryAwaitService);
    }

    @Test
    void protocolSuccessAdvancesWithoutMemberQuery() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        PullTaskManagerAdminWork work = work(PullTaskActionStatus.SUCCESS, false);
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.ready(work));
        when(transactions.confirmManagerAdmin(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        verify(transactions).confirmManagerAdmin(work, 1_000L);
        verify(transactions, never()).submitOrDefer(work, 1_000L);
        verifyNoInteractions(memberQueryAwaitService);
    }

    @Test
    void noOrderedCandidatesKeepsManagerResourceWaiting() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.completed(
                        PullTaskExecutionDispatchResult.DEFERRED));

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verifyNoInteractions(memberQueryAwaitService);
    }

    @Test
    void permanentlyFailedCandidatesRemainWaiting() {
        noOrderedCandidatesKeepsManagerResourceWaiting();
    }

    @Test
    void retryableFailedActionSubmitsANewAttempt() {
        PullTaskGroupExecution candidate = executionAtManagerAdmin();
        PullTaskManagerAdminWork work = work(PullTaskActionStatus.FAILED, true);
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskManagerAdminPreparation.ready(work));
        queryReturns(member("906", true), member("15", false));
        when(transactions.submitOrDefer(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);
        verify(transactions).submitOrDefer(work, 1_000L);
        verify(transactions, never()).confirmManagerAdmin(eq(work), anyLong());
        verifyNoInteractions(memberQueryAwaitService);
    }

    private static PullTaskManagerAdminWork work(
            PullTaskActionStatus status, boolean retryable) {
        PullTaskGroupAccount manager = account(501L, 15L, "15", PullTaskGroupAccountRole.MANAGER);
        PullTaskGroupAccount promoterRole = account(
                503L, 906L, "906", PullTaskGroupAccountRole.PROMOTER);
        PullTaskAccountAction action = new PullTaskAccountAction();
        action.setId(701L);
        action.setTaskId(100L);
        action.setGroupExecutionId(11L);
        action.setActorGroupAccountId(503L);
        action.setTargetGroupAccountId(501L);
        action.setActionStatus(status.code());
        action.setRetryable(retryable);
        action.setAttemptNo(1);
        return new PullTaskManagerAdminWork(
                7L, 100L, 11L, 2, "worker-1", "120363group@g.us",
                manager,
                new GroupExecutionAccount(906L, "web", "promoter-906", "906", true),
                promoterRole,
                action);
    }

    private static PullTaskGroupAccount account(
            long id, long accountId, String phone, PullTaskGroupAccountRole role) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
        return row;
    }

    private static PullTaskGroupExecution executionAtManagerAdmin() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setTenantId(7L);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.MANAGER_ADMIN.code());
        row.setGroupJid("120363group@g.us");
        row.setVersion(2);
        row.setLockOwner("worker-1");
        return row;
    }

    private void queryReturns(PullTaskMemberFact... facts) {
        when(memberQueryAwaitService.readOrDefer(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(PullTaskMemberQueryResult.available(701L, List.of(facts)));
    }

    private static PullTaskMemberFact member(String phone, boolean admin) {
        return new PullTaskMemberFact(
                phone + "@s.whatsapp.net", phone + "@s.whatsapp.net", phone,
                true, admin);
    }
}
