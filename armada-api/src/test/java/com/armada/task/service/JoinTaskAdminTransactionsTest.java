package com.armada.task.service;

import com.armada.platform.kafka.consumer.group.ProtocolJoinTaskAdminResult;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskAdminMapper;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskAdminObservation;
import com.armada.task.model.dto.JoinTaskAdminWork;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 成员级结果、重试与阶段推进的业务测试，SQL 由独立 H2 测试验证。 */
class JoinTaskAdminTransactionsTest {
    private final JoinTaskAdminMapper admins = mock(JoinTaskAdminMapper.class);
    private final JoinTaskMapper tasks = mock(JoinTaskMapper.class);
    private final JoinTaskResultMapper results = mock(JoinTaskResultMapper.class);
    private final ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
    private final JoinTaskAdminFacts facts = mock(JoinTaskAdminFacts.class);
    private final com.armada.task.mapper.JoinTaskCleanupMapper cleanups = mock(com.armada.task.mapper.JoinTaskCleanupMapper.class);
    private final JoinTaskCleanupTransactions cleanup = spy(new JoinTaskCleanupTransactions(cleanups, admins, tasks, results,
            mock(com.armada.group.service.WhatsappGroupBusinessDepartureService.class)));
    private final JoinTaskAdminTransactions service = new JoinTaskAdminTransactions(admins, tasks, outbox, facts, cleanup);
    private JoinTask task;
    private JoinTaskResult row;

    @BeforeEach
    void setup() {
        TenantContext.set(7L);
        task = new JoinTask(); task.setId(10L); task.setStatus("RUNNING"); task.setSetAdminEnabled(true);
        row = new JoinTaskResult(); row.setId(20L); row.setTenantId(7L); row.setJoinTaskId(10L);
        row.setAccountId(30L); row.setGroupJid("123@g.us"); row.setStatus("SUCCESS");
        row.setAdminStatus(2); row.setAdminCommandId("cmd-1"); row.setAdminAttemptNo(1);
        row.setAdminActorAccountId(40L); row.setAdminNextExecuteAt(500L); row.setAdminDeadlineAt(10000L);
        when(admins.lock(20L)).thenReturn(row);
        when(admins.update(any())).thenReturn(1);
        when(tasks.selectByTenantAndId(10L)).thenReturn(task);
        when(facts.targetJid(row)).thenReturn("12345@s.whatsapp.net");
        when(cleanups.insert(any())).thenReturn(1);
    }
    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void enabledCleanupIsEnqueuedAfterPromotionAndBlocksNextJoin() {
        task.setClearAdminsAndLeaveEnabled(true);
        service.apply(event("cmd-1", "SUCCESS", false));
        assertEquals(3, row.getAdminStatus());
        verify(cleanup).enqueue(eq(task), eq(row), anyLong());
        verifyNoInteractions(results);
        verify(tasks, never()).markDoneWhenNoPending(anyLong(), anyLong());
        service.apply(event("cmd-1", "SUCCESS", false));
        verify(cleanup, times(1)).enqueue(eq(task), eq(row), anyLong());
    }

    @Test
    void failedPromotionNeverEnqueuesCleanup() {
        task.setClearAdminsAndLeaveEnabled(true);
        service.apply(event("cmd-1", "FAILED", false));
        verify(cleanup, never()).enqueue(any(), any(), anyLong());
    }

    @Test
    void successCompletesOnlyAdminStageAndAdvancesSameAccount() {
        service.apply(event("cmd-1", "SUCCESS", false));
        assertEquals("SUCCESS", row.getStatus());
        assertEquals(3, row.getAdminStatus());
        assertTrue(row.isAdmin());
        verify(facts).recordSuccess(row, 900L, "e1");
        verify(results).activateNextPending(eq(10L), eq(30L), eq(20L), anyLong(), anyLong());
        verify(results, never()).markTerminalSuccess(anyLong(), anyString(), anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(tasks).markDoneWhenNoPending(eq(10L), anyLong());
    }

    @Test
    void failureKeepsJoinedFactAndNeverRejoins() {
        service.apply(event("cmd-1", "FAILED", false));
        assertEquals("SUCCESS", row.getStatus());
        assertEquals(4, row.getAdminStatus());
        assertTrue(row.getAdminReason().contains("TARGET_NOT_FOUND"));
        verify(outbox, never()).enqueueGroupJoinCommands(anyList());
    }

    @Test
    void unknownWaitsForRoleQueryWithoutFinishing() {
        service.apply(event("cmd-1", "UNKNOWN", true));
        assertEquals(5, row.getAdminStatus());
        verifyNoInteractions(results);
        verify(tasks, never()).markDoneWhenNoPending(anyLong(), anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    void staleOrRepeatedResultCannotAdvance() {
        service.apply(event("old-command", "SUCCESS", false));
        verify(admins, never()).update(any());
        verifyNoInteractions(results);
        row.setAdminStatus(3);
        service.apply(event("cmd-1", "SUCCESS", false));
        verify(admins, never()).update(any());
    }

    @Test
    void deletedTaskCannotDispatchOrComplete() {
        when(tasks.selectByTenantAndId(10L)).thenReturn(null);
        service.apply(event("cmd-1", "SUCCESS", false));
        verifyNoInteractions(results, outbox);
    }

    @Test
    void resourceWaitDoesNotCompleteBeforeDeadline() {
        service.observe(new JoinTaskAdminWork(task, row), new JoinTaskAdminObservation(
                JoinTaskAdminObservation.Kind.WAIT, null, "没有可用管理员"), 1000L);
        assertEquals(2, row.getAdminStatus());
        assertEquals(6000L, row.getAdminNextExecuteAt());
        verifyNoInteractions(results);
    }

    @Test
    void exhaustedAdminRetryRetainsJoinSuccess() {
        when(outbox.isJoinTaskAdminCommandSettled("cmd-1")).thenReturn(true);
        var actor = new ProtocolAccountRef(40L, ProtocolBackend.WEB, "actor", "67890");
        service.observe(new JoinTaskAdminWork(task, row), new JoinTaskAdminObservation(
                JoinTaskAdminObservation.Kind.READY, actor, ""), 1000L);
        assertEquals(4, row.getAdminStatus());
        assertEquals("SUCCESS", row.getStatus());
        verify(outbox, never()).enqueueJoinTaskAdminCommand(any());
    }

    @Test
    void unpublishedCommandWaitsEvenWhenRetryIsDisabled() {
        var actor = new ProtocolAccountRef(40L, ProtocolBackend.WEB, "actor", "67890");
        service.observe(new JoinTaskAdminWork(task, row), new JoinTaskAdminObservation(
                JoinTaskAdminObservation.Kind.READY, actor, ""), 1000L);
        assertEquals(2, row.getAdminStatus());
        assertEquals(31000L, row.getAdminNextExecuteAt());
        verifyNoInteractions(results);
        verify(outbox, never()).enqueueJoinTaskAdminCommand(any());
    }

    @Test
    void verifiedNotAdminRetriesWithNewCommandAndPreservesJoinAttempt() {
        task.setRetryEnabled(true); task.setRetryLimit(2);
        when(outbox.isJoinTaskAdminCommandSettled("cmd-1")).thenReturn(true);
        when(outbox.enqueueJoinTaskAdminCommand(any())).thenReturn(
                new com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult(null, java.util.List.of("cmd-2"), 1));
        var actor = new ProtocolAccountRef(41L, ProtocolBackend.ANDROID, "actor-2", "67891");
        service.observe(new JoinTaskAdminWork(task, row), new JoinTaskAdminObservation(
                JoinTaskAdminObservation.Kind.READY, actor, ""), 1000L);
        assertEquals("cmd-2", row.getAdminCommandId());
        assertEquals(2, row.getAdminAttemptNo());
        assertEquals(41L, row.getAdminActorAccountId());
        assertEquals("SUCCESS", row.getStatus());
        verifyNoInteractions(results);
    }

    @Test
    void deadlineTerminatesResourceWaitWithoutChangingJoinResult() {
        service.observe(new JoinTaskAdminWork(task, row), new JoinTaskAdminObservation(
                JoinTaskAdminObservation.Kind.WAIT, null, "没有可用管理员"), 10000L);
        assertEquals(4, row.getAdminStatus());
        assertEquals("SUCCESS", row.getStatus());
        assertTrue(row.getAdminReason().contains("超时"));
        verify(outbox).cancelJoinTaskAdminCommand(eq("cmd-1"), anyLong());
    }

    private ProtocolJoinTaskAdminResult event(String command, String outcome, boolean retryable) {
        return new ProtocolJoinTaskAdminResult(7L, 10L, 20L, command, 1, 40L, "actor", "123@g.us",
                "12345@s.whatsapp.net", outcome, "TARGET_NOT_FOUND", retryable, 900L, "e1");
    }
}
