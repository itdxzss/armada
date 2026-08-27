package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolGroupJoinCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.service.JoinTaskIntervalPolicy;
import com.armada.task.service.JoinTaskInviteCodeParser;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinTaskDispatchTransactionServiceTest {

    @Mock private JoinTaskResultMapper resultMapper;
    @Mock private JoinTaskMapper taskMapper;
    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private ProtocolCommandOutboxService outboxService;

    private JoinTaskDispatchTransactionService service;

    @BeforeEach
    void setUp() {
        service = new JoinTaskDispatchTransactionService(
                resultMapper, taskMapper, accountLookupService, outboxService,
                new JoinTaskInviteCodeParser(), new JoinTaskIntervalPolicy());
    }

    @AfterEach
    void clearContexts() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void dispatchTenant_enqueuesOrderedWebAndAndroidCommandsThenMarksSubmitted() {
        JoinTaskResult web = row(26L, 9L, 382L, "https://chat.whatsapp.com/WEB123", 0);
        JoinTaskResult android = row(27L, 9L, 383L, "ANDROID123", 2);
        when(resultMapper.selectDueForUpdate(1L, List.of(26L, 27L), 10_000L)).thenReturn(List.of(web, android));
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(9L, 501L, 5));
        when(accountLookupService.findActiveProtocolRefs(List.of(382L, 383L))).thenReturn(List.of(
                new ProtocolAccountRef(382L, ProtocolBackend.WEB, "acc-web", "911"),
                new ProtocolAccountRef(383L, ProtocolBackend.ANDROID, "acc-android", "922")));
        when(outboxService.enqueueGroupJoinCommands(anyList())).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(501L);
            return new ProtocolCommandOutboxEnqueueResult(
                    "join-task:9", List.of("cmd-web", "cmd-android"), 2);
        });
        when(resultMapper.markSubmitted(26L, "cmd-web", 1, 10_000L)).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(501L);
            return 1;
        });
        when(resultMapper.markSubmitted(27L, "cmd-android", 3, 10_000L)).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(501L);
            return 1;
        });

        JoinTaskDispatchStats stats = service.dispatchTenant(1L, List.of(26L, 27L), 10_000L);

        assertThat(stats).isEqualTo(new JoinTaskDispatchStats(2, 2, 2, 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupJoinCommandRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueGroupJoinCommands(captor.capture());
        assertThat(captor.getValue()).extracting(
                ProtocolGroupJoinCommandRequest::protocolBackend,
                ProtocolGroupJoinCommandRequest::inviteCode,
                ProtocolGroupJoinCommandRequest::attemptNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ProtocolBackend.WEB, "WEB123", 1),
                        org.assertj.core.groups.Tuple.tuple(ProtocolBackend.ANDROID, "ANDROID123", 3));
        verify(resultMapper).markSubmitted(26L, "cmd-web", 1, 10_000L);
        verify(resultMapper).markSubmitted(27L, "cmd-android", 3, 10_000L);
    }

    @Test
    void dispatchTenant_missingAccountTerminatesRowAndSchedulesNext() {
        JoinTaskResult row = row(26L, 9L, 382L, "CODE123", 0);
        when(resultMapper.selectDueForUpdate(1L, List.of(26L), 10_000L)).thenReturn(List.of(row));
        when(accountLookupService.findActiveProtocolRefs(List.of(382L))).thenReturn(List.of());
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(9L, 501L, 5));
        when(resultMapper.markTerminalFailure(26L, "ACCOUNT_NOT_FOUND", 10_000L)).thenReturn(1);

        JoinTaskDispatchStats stats = service.dispatchTenant(1L, List.of(26L), 10_000L);

        assertThat(stats).isEqualTo(new JoinTaskDispatchStats(1, 1, 0, 1));
        verify(resultMapper).markTerminalFailure(26L, "ACCOUNT_NOT_FOUND", 10_000L);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
        verify(outboxService, never()).enqueueGroupJoinCommands(anyList());
    }

    @Test
    void dispatchTenant_hundredDistinctAccountsAreEnqueuedInOneBatch() {
        List<Long> resultIds = IntStream.range(0, 100).mapToObj(i -> 1_000L + i).toList();
        List<JoinTaskResult> rows = IntStream.range(0, 100)
                .mapToObj(i -> row(1_000L + i, 9L, 2_000L + i, "CODE" + i, 0))
                .toList();
        List<ProtocolAccountRef> refs = IntStream.range(0, 100)
                .mapToObj(i -> new ProtocolAccountRef(
                        2_000L + i, ProtocolBackend.WEB, "acc-" + i, "91" + i))
                .toList();
        List<String> commandIds = IntStream.range(0, 100).mapToObj(i -> "cmd-" + i).toList();
        when(resultMapper.selectDueForUpdate(1L, resultIds, 10_000L)).thenReturn(rows);
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(9L, 501L, 5));
        when(accountLookupService.findActiveProtocolRefs(anyList())).thenReturn(refs);
        when(outboxService.enqueueGroupJoinCommands(anyList())).thenReturn(
                new ProtocolCommandOutboxEnqueueResult("join-task:9", commandIds, 100));
        when(resultMapper.markSubmitted(anyLong(), anyString(), anyInt(), eq(10_000L))).thenReturn(1);

        JoinTaskDispatchStats stats = service.dispatchTenant(1L, resultIds, 10_000L);

        assertThat(stats).isEqualTo(new JoinTaskDispatchStats(100, 100, 100, 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupJoinCommandRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueGroupJoinCommands(captor.capture());
        assertThat(captor.getValue()).hasSize(100);
    }

    @Test
    void dispatchTenant_partitionsAccountReadsByTaskOwnerAndKeepsCommandOrder() {
        JoinTaskResult first = row(26L, 9L, 382L, "FIRST", 0);
        JoinTaskResult second = row(27L, 10L, 383L, "SECOND", 0);
        when(resultMapper.selectDueForUpdate(1L, List.of(26L, 27L), 10_000L))
                .thenReturn(List.of(first, second));
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(9L, 501L, 5));
        when(taskMapper.selectByTenantAndId(10L)).thenReturn(task(10L, 777L, 5));
        when(accountLookupService.findActiveProtocolRefs(List.of(382L))).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(501L);
            return List.of(new ProtocolAccountRef(382L, ProtocolBackend.WEB, "acc-first", "911"));
        });
        when(accountLookupService.findActiveProtocolRefs(List.of(383L))).thenAnswer(ignored -> {
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(777L);
            return List.of(new ProtocolAccountRef(383L, ProtocolBackend.ANDROID, "acc-second", "922"));
        });
        when(outboxService.enqueueGroupJoinCommands(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ProtocolGroupJoinCommandRequest> commands = invocation.getArgument(0, List.class);
            ProtocolGroupJoinCommandRequest command = commands.get(0);
            Long expectedOwner = command.joinTaskId() == 9L ? 501L : 777L;
            assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(expectedOwner);
            return new ProtocolCommandOutboxEnqueueResult(
                    "join-task:" + command.joinTaskId(), List.of("cmd-" + command.joinTaskId()), 1);
        });
        when(resultMapper.markSubmitted(anyLong(), anyString(), anyInt(), eq(10_000L))).thenReturn(1);

        JoinTaskDispatchStats stats = service.dispatchTenant(1L, List.of(26L, 27L), 10_000L);

        assertThat(stats).isEqualTo(new JoinTaskDispatchStats(2, 2, 2, 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupJoinCommandRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxService, times(2)).enqueueGroupJoinCommands(captor.capture());
        assertThat(captor.getAllValues().stream().flatMap(List::stream).toList())
                .extracting(ProtocolGroupJoinCommandRequest::joinTaskId)
                .containsExactly(9L, 10L);
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void dispatchTenant_historicalUnownedTaskFailsClosedWithoutReadingAccounts() {
        JoinTaskResult row = row(26L, 9L, 382L, "CODE123", 0);
        when(resultMapper.selectDueForUpdate(1L, List.of(26L), 10_000L)).thenReturn(List.of(row));
        when(taskMapper.selectByTenantAndId(9L)).thenReturn(task(9L, null, 5));
        when(resultMapper.markTerminalFailure(26L, "DATA_OWNER_MISSING", 10_000L)).thenReturn(1);

        JoinTaskDispatchStats stats = service.dispatchTenant(1L, List.of(26L), 10_000L);

        assertThat(stats).isEqualTo(new JoinTaskDispatchStats(1, 1, 0, 1));
        verify(accountLookupService, never()).findActiveProtocolRefs(anyList());
        verify(resultMapper).markTerminalFailure(26L, "DATA_OWNER_MISSING", 10_000L);
        verify(resultMapper).activateNextPending(9L, 382L, 26L, 15_000L, 10_000L);
        verify(outboxService, never()).enqueueGroupJoinCommands(anyList());
        assertThat(DataScopeContext.current()).isEmpty();
    }

    private static JoinTaskResult row(Long id, Long taskId, Long accountId, String link, int attempt) {
        JoinTaskResult row = new JoinTaskResult();
        row.setId(id);
        row.setJoinTaskId(taskId);
        row.setAccountId(accountId);
        row.setLink(link);
        row.setAttemptNo(attempt);
        return row;
    }

    private static JoinTask task(Long taskId, Long ownerUserId, int intervalSeconds) {
        JoinTask task = new JoinTask();
        task.setId(taskId);
        task.setOwnerUserId(ownerUserId);
        task.setDistributionMode("FIXED_ACCOUNTS_PER_LINK");
        task.setFixedIntervalMinSec(intervalSeconds);
        task.setFixedIntervalMaxSec(intervalSeconds);
        return task;
    }
}
