package com.armada.task.worker;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinResultStatus;
import com.armada.task.model.enums.JoinTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinTaskWorkerTest {

    @Mock
    private JoinTaskMapper joinTaskMapper;

    @Mock
    private JoinTaskResultMapper resultMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private GroupJoinPort groupJoinPort;

    private JoinTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new JoinTaskWorker(joinTaskMapper, resultMapper, accountMapper, groupJoinPort, Runnable::run, millis -> {
        });
    }

    @Test
    void runTask_marksRowSuccessOnlyWhenJoinActuallyJoined() {
        JoinTask task = runningTask(7L);
        JoinTaskResult row = pendingRow(70L, 100L, "https://chat.whatsapp.com/ABC123");
        Account account = account(100L, "acc_861001");
        when(joinTaskMapper.selectByTenantAndId(7L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(7L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(groupJoinPort.join("acc_861001", row.getLink()))
                .thenReturn(new GroupJoinResult("120363joined@g.us", true));

        worker.runTask(1L, 7L);

        verify(resultMapper).updateResultSuccess(eq(70L), eq("120363joined@g.us"), anyLong());
        verify(resultMapper, never()).updateResultFailed(eq(70L), eq(JoinTaskWorker.REASON_JOIN_PENDING_APPROVAL), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(7L));
        verify(joinTaskMapper).updateTaskStatus(eq(7L), eq(JoinTaskStatus.DONE), anyLong());
    }

    @Test
    void runTask_marksPendingApprovalAsFailedInsteadOfSuccess() {
        JoinTask task = runningTask(8L);
        JoinTaskResult row = pendingRow(80L, 200L, "https://chat.whatsapp.com/PENDING");
        Account account = account(200L, "acc_862002");
        when(joinTaskMapper.selectByTenantAndId(8L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(8L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(200L)).thenReturn(account);
        when(groupJoinPort.join("acc_862002", row.getLink()))
                .thenReturn(new GroupJoinResult("120363pending@g.us", false));

        worker.runTask(1L, 8L);

        verify(resultMapper, never()).updateResultSuccess(eq(80L), eq("120363pending@g.us"), anyLong());
        verify(resultMapper).updateResultFailed(eq(80L), eq(JoinTaskWorker.REASON_JOIN_PENDING_APPROVAL), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(8L));
        verify(joinTaskMapper).updateTaskStatus(eq(8L), eq(JoinTaskStatus.DONE), anyLong());
    }

    @Test
    void runTask_marksRowFailedWhenAccountCannotBeResolved() {
        JoinTask task = runningTask(9L);
        JoinTaskResult row = pendingRow(90L, 300L, "https://chat.whatsapp.com/MISSING");
        when(joinTaskMapper.selectByTenantAndId(9L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(9L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(300L)).thenReturn(null);

        worker.runTask(1L, 9L);

        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(eq(90L), eq(JoinTaskWorker.REASON_ACCOUNT_NOT_FOUND), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(9L));
    }

    @Test
    void startAsync_marksTaskFailedWhenExecutorRejectsSubmission() {
        JoinTaskWorker rejectingWorker = new JoinTaskWorker(
                joinTaskMapper,
                resultMapper,
                accountMapper,
                groupJoinPort,
                command -> {
                    throw new RejectedExecutionException("queue full");
                },
                millis -> {
                });

        rejectingWorker.startAsync(1L, 10L);

        verify(joinTaskMapper).updateTaskStatus(eq(10L), eq(JoinTaskStatus.FAILED), anyLong());
    }

    @Test
    void runTask_marksTaskFailedWhenUnexpectedWorkerErrorEscapes() {
        when(joinTaskMapper.selectByTenantAndId(11L)).thenThrow(new IllegalStateException("db unavailable"));

        assertThatCode(() -> worker.runTask(1L, 11L)).doesNotThrowAnyException();

        verify(joinTaskMapper).updateTaskStatus(eq(11L), eq(JoinTaskStatus.FAILED), anyLong());
    }

    private static JoinTask runningTask(Long id) {
        JoinTask task = new JoinTask();
        task.setId(id);
        task.setStatus(JoinTaskStatus.RUNNING);
        task.setDistributionMode(DistributionMode.FIXED_ACCOUNTS_PER_LINK);
        task.setFixedIntervalMinSec(0);
        task.setFixedIntervalMaxSec(0);
        return task;
    }

    private static JoinTaskResult pendingRow(Long id, Long accountId, String link) {
        JoinTaskResult row = new JoinTaskResult();
        row.setId(id);
        row.setAccountId(accountId);
        row.setLink(link);
        row.setStatus(JoinResultStatus.PENDING);
        return row;
    }

    private static Account account(Long id, String protocolAccountId) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolAccountId(protocolAccountId);
        return account;
    }
}
