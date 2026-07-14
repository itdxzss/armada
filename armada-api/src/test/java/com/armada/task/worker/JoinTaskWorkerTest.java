package com.armada.task.worker;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.shared.tenant.TenantContext;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
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
    private AccountStateMapper accountStateMapper;

    @Mock
    private GroupJoinPort groupJoinPort;

    @Mock
    private AccountRuntimeStatusPort accountRuntimeStatusPort;

    private JoinTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new JoinTaskWorker(
                joinTaskMapper,
                resultMapper,
                accountMapper,
                accountStateMapper,
                groupJoinPort,
                accountRuntimeStatusPort,
                Runnable::run,
                Runnable::run,
                millis -> {
                });
    }

    @Test
    void runTask_marksRowSuccessOnlyWhenJoinActuallyJoined() {
        JoinTask task = runningTask(7L);
        JoinTaskResult row = pendingRow(70L, 100L, "https://chat.whatsapp.com/ABC123");
        Account account = account(100L, "WEB", "acc_861001", "861001");
        GroupJoinCommand command = new GroupJoinCommand(
                new ProtocolAccountRef(100L, ProtocolBackend.WEB, "acc_861001", "861001"),
                row.getLink(),
                "join-task-result:70");
        when(joinTaskMapper.selectByTenantAndId(7L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(7L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(100L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(command.account()))
                .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command))
                .thenReturn(new GroupJoinResult("120363joined@g.us", GroupJoinOutcome.JOINED));

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
        Account account = account(200L, "WEB", "acc_862002", "862002");
        GroupJoinCommand command = new GroupJoinCommand(
                new ProtocolAccountRef(200L, ProtocolBackend.WEB, "acc_862002", "862002"),
                row.getLink(),
                "join-task-result:80");
        when(joinTaskMapper.selectByTenantAndId(8L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(8L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(200L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(command.account()))
                .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command))
                .thenReturn(new GroupJoinResult("120363pending@g.us", GroupJoinOutcome.PENDING_APPROVAL));

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

        verifyNoInteractions(accountRuntimeStatusPort);
        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(eq(90L), eq(JoinTaskWorker.REASON_ACCOUNT_NOT_FOUND), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(9L));
    }

    @Test
    void runTask_marksRowOfflineWhenProtocolStatusNotFound() {
        JoinTask task = runningTask(12L);
        JoinTaskResult row = pendingRow(120L, 400L, "https://chat.whatsapp.com/STALE");
        Account account = account(400L, "WEB", "acc_864004", "864004");
        ProtocolException notFound = new ProtocolException(
                ProtocolErrorCode.ACCOUNT_NOT_FOUND,
                ProtocolException.Metadata.of(404, "ACCOUNT_NOT_FOUND", null, null),
                "protocol account not found",
                null);
        when(joinTaskMapper.selectByTenantAndId(12L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(12L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(400L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(new ProtocolAccountRef(
                400L, ProtocolBackend.WEB, "acc_864004", "864004"))).thenThrow(notFound);

        worker.runTask(1L, 12L);

        ArgumentCaptor<AccountState> stateCaptor = forClass(AccountState.class);
        verify(accountStateMapper).updateLoginState(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getAccountId()).isEqualTo(400L);
        assertThat(stateCaptor.getValue().getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(stateCaptor.getValue().getStateSource()).isEqualTo("JOIN_TASK_STATUS_NOT_FOUND");
        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(eq(120L), eq(JoinTaskWorker.REASON_ACCOUNT_NOT_ONLINE), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(12L));
    }

    @Test
    void runTask_skipsJoinWhenProtocolStatusIsNotOnline() {
        JoinTask task = runningTask(13L);
        JoinTaskResult row = pendingRow(130L, 500L, "https://chat.whatsapp.com/OFFLINE");
        Account account = account(500L, "WEB", "acc_865005", "865005");
        when(joinTaskMapper.selectByTenantAndId(13L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(13L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(500L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(new ProtocolAccountRef(
                500L, ProtocolBackend.WEB, "acc_865005", "865005")))
                .thenReturn(new ProtocolAccountRuntimeStatus("RECONNECTING"));

        worker.runTask(1L, 13L);

        ArgumentCaptor<AccountState> stateCaptor = forClass(AccountState.class);
        verify(accountStateMapper).updateLoginState(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getAccountId()).isEqualTo(500L);
        assertThat(stateCaptor.getValue().getLoginState()).isEqualTo(AccountLoginStateCode.OFFLINE);
        assertThat(stateCaptor.getValue().getStateSource()).isEqualTo("JOIN_TASK_STATUS");
        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(eq(130L), eq(JoinTaskWorker.REASON_ACCOUNT_NOT_ONLINE), anyLong());
        verify(joinTaskMapper).refreshCounters(eq(13L));
    }

    @Test
    void runTask_retryLimitTwoAllowsInitialAttemptPlusTwoRetriesAndCanSucceed() {
        JoinTask task = runningTask(14L);
        task.setRetryEnabled(true);
        task.setRetryLimit(2);
        JoinTaskResult row = pendingRow(140L, 600L, "https://chat.whatsapp.com/RETRY_OK");
        Account account = account(600L, "WEB", "acc_866006", "866006");
        ProtocolAccountRef ref = new ProtocolAccountRef(
                600L, ProtocolBackend.WEB, "acc_866006", "866006");
        GroupJoinCommand command = new GroupJoinCommand(
                ref, row.getLink(), "join-task-result:140");
        when(joinTaskMapper.selectByTenantAndId(14L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(14L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(600L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(ref)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command))
                .thenThrow(new IllegalStateException("temporary-1"))
                .thenThrow(new IllegalStateException("temporary-2"))
                .thenReturn(new GroupJoinResult("120363retry@g.us", GroupJoinOutcome.JOINED));

        worker.runTask(1L, 14L);

        verify(groupJoinPort, times(3)).join(command);
        verify(resultMapper).updateResultSuccess(eq(140L), eq("120363retry@g.us"), anyLong());
        verify(resultMapper, never()).updateResultFailed(eq(140L), eq("temporary-1"), anyLong());
    }

    @Test
    void runTask_exhaustedRetriesFailOnlyAfterThreeProtocolCalls() {
        JoinTask task = runningTask(15L);
        task.setRetryEnabled(true);
        task.setRetryLimit(2);
        JoinTaskResult row = pendingRow(150L, 700L, "https://chat.whatsapp.com/RETRY_FAIL");
        Account account = account(700L, "WEB", "acc_867007", "867007");
        ProtocolAccountRef ref = new ProtocolAccountRef(
                700L, ProtocolBackend.WEB, "acc_867007", "867007");
        GroupJoinCommand command = new GroupJoinCommand(
                ref, row.getLink(), "join-task-result:150");
        when(joinTaskMapper.selectByTenantAndId(15L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(15L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(700L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(ref)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command))
                .thenThrow(new IllegalStateException("temporary"));

        worker.runTask(1L, 15L);

        verify(groupJoinPort, times(3)).join(command);
        verify(resultMapper).updateResultFailed(eq(150L), eq("temporary"), anyLong());
    }

    @Test
    void runTask_doesNotRetryExplicitPermanentInviteFailure() {
        JoinTask task = runningTask(16L);
        task.setRetryEnabled(true);
        task.setRetryLimit(2);
        JoinTaskResult row = pendingRow(160L, 800L, "https://chat.whatsapp.com/REVOKED");
        Account account = account(800L, "WEB", "acc_868008", "868008");
        ProtocolAccountRef ref = new ProtocolAccountRef(
                800L, ProtocolBackend.WEB, "acc_868008", "868008");
        GroupJoinCommand command = new GroupJoinCommand(
                ref, row.getLink(), "join-task-result:160");
        ProtocolException revoked = new ProtocolException(
                ProtocolErrorCode.INVITE_REVOKED,
                ProtocolException.Metadata.of(410, "INVITE_REVOKED", null, null, false),
                "invite revoked",
                null);
        when(joinTaskMapper.selectByTenantAndId(16L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(16L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(800L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(ref)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command)).thenThrow(revoked);

        worker.runTask(1L, 16L);

        verify(groupJoinPort).join(command);
        verify(resultMapper).updateResultFailed(eq(160L), eq("INVITE_REVOKED"), anyLong());
    }

    @Test
    void runTask_executesDifferentAccountLanesConcurrentlyWithTenantContext() throws Exception {
        ExecutorService lanePool = Executors.newFixedThreadPool(2);
        JoinTaskWorker concurrentWorker = new JoinTaskWorker(
                joinTaskMapper,
                resultMapper,
                accountMapper,
                accountStateMapper,
                groupJoinPort,
                accountRuntimeStatusPort,
                Runnable::run,
                lanePool,
                millis -> {
                });
        JoinTask task = runningTask(17L);
        task.setDistributionMode(DistributionMode.FIXED_ACCOUNT_MULTI_LINK);
        JoinTaskResult rowA = pendingRow(170L, 900L, "https://chat.whatsapp.com/A1");
        JoinTaskResult rowB = pendingRow(171L, 901L, "https://chat.whatsapp.com/B1");
        ProtocolAccountRef refA = new ProtocolAccountRef(900L, ProtocolBackend.WEB, "acc_A", "900");
        ProtocolAccountRef refB = new ProtocolAccountRef(901L, ProtocolBackend.WEB, "acc_B", "901");
        GroupJoinCommand commandA = new GroupJoinCommand(refA, rowA.getLink(), "join-task-result:170");
        GroupJoinCommand commandB = new GroupJoinCommand(refB, rowB.getLink(), "join-task-result:171");
        when(joinTaskMapper.selectByTenantAndId(17L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(17L)).thenReturn(List.of(rowA, rowB), List.of());
        when(accountMapper.selectActiveById(900L)).thenReturn(account(900L, "WEB", "acc_A", "900"));
        when(accountMapper.selectActiveById(901L)).thenReturn(account(901L, "WEB", "acc_B", "901"));
        when(accountRuntimeStatusPort.status(refA)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(accountRuntimeStatusPort.status(refB)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        CountDownLatch bothEntered = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Long> observedTenants = new ConcurrentLinkedQueue<>();
        when(groupJoinPort.join(any(GroupJoinCommand.class))).thenAnswer(invocation -> {
                    observedTenants.add(TenantContext.get());
                    bothEntered.countDown();
                    if (!bothEntered.await(2, TimeUnit.SECONDS)) {
                        overlapped.set(false);
                    }
                    GroupJoinCommand command = invocation.getArgument(0);
                    return new GroupJoinResult(
                            command.account().protocolAccountId() + "@g.us",
                            GroupJoinOutcome.JOINED);
                });

        try {
            concurrentWorker.runTask(1L, 17L);
        } finally {
            lanePool.shutdownNow();
        }

        assertThat(lanePool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(overlapped).isTrue();
        assertThat(observedTenants).containsExactlyInAnyOrder(1L, 1L);
        verify(groupJoinPort).join(commandA);
        verify(groupJoinPort).join(commandB);
        verify(joinTaskMapper).updateTaskStatus(eq(17L), eq(JoinTaskStatus.DONE), anyLong());
        verify(joinTaskMapper, never()).updateTaskStatus(eq(17L), eq(JoinTaskStatus.FAILED), anyLong());
    }

    @Test
    void runTask_waitsOnlyBetweenRowsInTheSameAccountLane() {
        List<Long> sleeps = new CopyOnWriteArrayList<>();
        JoinTaskWorker intervalWorker = new JoinTaskWorker(
                joinTaskMapper,
                resultMapper,
                accountMapper,
                accountStateMapper,
                groupJoinPort,
                accountRuntimeStatusPort,
                Runnable::run,
                Runnable::run,
                sleeps::add);
        JoinTask task = runningTask(18L);
        task.setDistributionMode(DistributionMode.FIXED_ACCOUNT_MULTI_LINK);
        task.setMultiIntervalMinSec(1);
        task.setMultiIntervalMaxSec(1);
        JoinTaskResult rowA1 = pendingRow(180L, 910L, "https://chat.whatsapp.com/A1");
        JoinTaskResult rowB1 = pendingRow(181L, 911L, "https://chat.whatsapp.com/B1");
        JoinTaskResult rowA2 = pendingRow(182L, 910L, "https://chat.whatsapp.com/A2");
        ProtocolAccountRef refA = new ProtocolAccountRef(910L, ProtocolBackend.WEB, "acc_A", "910");
        ProtocolAccountRef refB = new ProtocolAccountRef(911L, ProtocolBackend.WEB, "acc_B", "911");
        GroupJoinCommand commandA1 = new GroupJoinCommand(refA, rowA1.getLink(), "join-task-result:180");
        GroupJoinCommand commandB1 = new GroupJoinCommand(refB, rowB1.getLink(), "join-task-result:181");
        GroupJoinCommand commandA2 = new GroupJoinCommand(refA, rowA2.getLink(), "join-task-result:182");
        when(joinTaskMapper.selectByTenantAndId(18L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(18L)).thenReturn(List.of(rowA1, rowB1, rowA2), List.of());
        when(accountMapper.selectActiveById(910L)).thenReturn(account(910L, "WEB", "acc_A", "910"));
        when(accountMapper.selectActiveById(911L)).thenReturn(account(911L, "WEB", "acc_B", "911"));
        when(accountRuntimeStatusPort.status(refA)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(accountRuntimeStatusPort.status(refB)).thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(any(GroupJoinCommand.class)))
                .thenReturn(new GroupJoinResult("120363joined@g.us", GroupJoinOutcome.JOINED));

        intervalWorker.runTask(1L, 18L);

        assertThat(sleeps).containsExactly(1_000L);
        InOrder order = inOrder(groupJoinPort);
        order.verify(groupJoinPort).join(commandA1);
        order.verify(groupJoinPort).join(commandA2);
        order.verify(groupJoinPort).join(commandB1);
    }

    @Test
    void startAsync_marksTaskFailedWhenExecutorRejectsSubmission() {
        JoinTaskWorker rejectingWorker = new JoinTaskWorker(
                joinTaskMapper,
                resultMapper,
                accountMapper,
                accountStateMapper,
                groupJoinPort,
                accountRuntimeStatusPort,
                command -> {
                    throw new RejectedExecutionException("queue full");
                },
                Runnable::run,
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

    @Test
    void runTask_routesWebAndAndroidRowsThroughCanonicalCommands() {
        JoinTask task = runningTask(20L);
        JoinTaskResult webRow = pendingRow(201L, 1001L, "https://chat.whatsapp.com/WEB001");
        JoinTaskResult androidRow = pendingRow(202L, 1002L, "https://chat.whatsapp.com/ANDROID002");
        Account webAccount = account(1001L, "WEB", "acc_861001", "861001");
        Account androidAccount = account(1002L, "ANDROID", "acc_919002", "919002");
        ProtocolAccountRef webRef = new ProtocolAccountRef(
                1001L, ProtocolBackend.WEB, "acc_861001", "861001");
        ProtocolAccountRef androidRef = new ProtocolAccountRef(
                1002L, ProtocolBackend.ANDROID, "acc_919002", "919002");
        GroupJoinCommand webCommand = new GroupJoinCommand(
                webRef, webRow.getLink(), "join-task-result:201");
        GroupJoinCommand androidCommand = new GroupJoinCommand(
                androidRef, androidRow.getLink(), "join-task-result:202");

        when(joinTaskMapper.selectByTenantAndId(20L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(20L))
                .thenReturn(List.of(webRow, androidRow), List.of());
        when(accountMapper.selectActiveById(1001L)).thenReturn(webAccount);
        when(accountMapper.selectActiveById(1002L)).thenReturn(androidAccount);
        when(accountRuntimeStatusPort.status(webRef))
                .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(accountRuntimeStatusPort.status(androidRef))
                .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(webCommand))
                .thenReturn(new GroupJoinResult("120363web@g.us", GroupJoinOutcome.JOINED));
        when(groupJoinPort.join(androidCommand))
                .thenReturn(new GroupJoinResult("120363android@g.us", GroupJoinOutcome.JOINED));

        worker.runTask(1L, 20L);

        verify(groupJoinPort).join(webCommand);
        verify(groupJoinPort).join(androidCommand);
        verify(resultMapper).updateResultSuccess(eq(201L), eq("120363web@g.us"), anyLong());
        verify(resultMapper).updateResultSuccess(eq(202L), eq("120363android@g.us"), anyLong());
    }

    @Test
    void runTask_doesNotMarkAccountOfflineWhenRuntimeStatusCallHasNetworkFailure() {
        JoinTask task = runningTask(21L);
        JoinTaskResult row = pendingRow(211L, 1101L, "https://chat.whatsapp.com/NETWORK");
        Account account = account(1101L, "ANDROID", "acc_919101", "919101");
        ProtocolAccountRef ref = new ProtocolAccountRef(
                1101L, ProtocolBackend.ANDROID, "acc_919101", "919101");
        when(joinTaskMapper.selectByTenantAndId(21L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(21L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(1101L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(ref)).thenThrow(new ProtocolException(
                ProtocolErrorCode.NETWORK,
                ProtocolException.Metadata.of(0, "ECONNRESET", null, null),
                "network",
                null));

        worker.runTask(1L, 21L);

        verifyNoInteractions(accountStateMapper);
        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(eq(211L), eq("NETWORK"), anyLong());
    }

    @Test
    void runTask_neverMarksUnconfirmedAndroidJoinAsSuccess() {
        JoinTask task = runningTask(22L);
        JoinTaskResult row = pendingRow(221L, 1201L, "https://chat.whatsapp.com/UNCONFIRMED");
        Account account = account(1201L, "ANDROID", "acc_919201", "919201");
        ProtocolAccountRef ref = new ProtocolAccountRef(
                1201L, ProtocolBackend.ANDROID, "acc_919201", "919201");
        GroupJoinCommand command = new GroupJoinCommand(
                ref, row.getLink(), "join-task-result:221");
        when(joinTaskMapper.selectByTenantAndId(22L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(22L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(1201L)).thenReturn(account);
        when(accountRuntimeStatusPort.status(ref))
                .thenReturn(new ProtocolAccountRuntimeStatus("ONLINE"));
        when(groupJoinPort.join(command)).thenThrow(new ProtocolException(
                ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,
                "unconfirmed"));

        worker.runTask(1L, 22L);

        verify(resultMapper, never()).updateResultSuccess(eq(221L), any(), anyLong());
        verify(resultMapper).updateResultFailed(
                eq(221L), eq("JOIN_RESULT_UNCONFIRMED"), anyLong());
    }

    @Test
    void runTask_rejectsAccountWithBlankWsPhoneBeforeProtocolCalls() {
        JoinTask task = runningTask(23L);
        JoinTaskResult row = pendingRow(231L, 1301L, "https://chat.whatsapp.com/NO_PHONE");
        Account account = account(1301L, "ANDROID", "acc_919301", " ");
        when(joinTaskMapper.selectByTenantAndId(23L)).thenReturn(task);
        when(resultMapper.selectPendingResultsByTask(23L)).thenReturn(List.of(row), List.of());
        when(accountMapper.selectActiveById(1301L)).thenReturn(account);

        worker.runTask(1L, 23L);

        verifyNoInteractions(accountRuntimeStatusPort);
        verifyNoInteractions(groupJoinPort);
        verify(resultMapper).updateResultFailed(
                eq(231L), eq(JoinTaskWorker.REASON_ACCOUNT_NOT_FOUND), anyLong());
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

    private static Account account(Long id, String protocolId, String protocolAccountId, String wsPhone) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolId(protocolId);
        account.setProtocolAccountId(protocolAccountId);
        account.setWsPhone(wsPhone);
        return account;
    }

}
