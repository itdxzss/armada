package com.armada.hyperlink.task.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 自然完成计费门禁和 UNKNOWN 原命令恢复时钟边界。 */
class HyperlinkCompletionAndUnknownRecoveryTest {
    private static final long NOW = 3_000_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    @Test
    void completionWaitsForEveryRecipientAndUsageSlot() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRuntimeMapper runtime = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        when(recipients.countUnsettledByTaskId(11L)).thenReturn(1);
        HyperlinkTaskCompletionService service = new HyperlinkTaskCompletionService(
                recipients, usages, runtime, billing, CLOCK);

        service.completeIfReady(11L);

        verify(billing, never()).finalizeBilling(11L);
        verify(runtime, never()).markCompletedIfIdle(11L, NOW);
    }

    @Test
    void completionFinalizesExistingBillingSagaBeforeRuntimeTerminalState() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRuntimeMapper runtime = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkTaskCompletionService service = new HyperlinkTaskCompletionService(
                recipients, usages, runtime, billing, CLOCK);

        service.completeIfReady(11L);

        var order = org.mockito.Mockito.inOrder(billing, runtime);
        order.verify(billing).finalizeBilling(11L);
        order.verify(runtime).markCompletedIfIdle(11L, NOW);
    }

    @Test
    void unknownReplaysTheExactWebCommandAndOnlyReschedulesThatRecipient() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        MessageCommandRecoveryPort recovery = mock(MessageCommandRecoveryPort.class);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkUnknownResultRecoveryService service =
                new HyperlinkUnknownResultRecoveryService(
                        recipients, recovery, dispatchGuard, CLOCK);
        HyperlinkReconciliationCandidate candidate =
                new HyperlinkReconciliationCandidate(
                        7L, 11L, 13L, 17L, "hl:7:11:13", 1, 1L);

        service.recover(candidate);

        verify(recovery).replay(7L, "hl:7:11:13", NOW);
        verify(dispatchGuard).renew(17L, "hl:7:11:13");
        verify(recipients).scheduleReconciliation("hl:7:11:13", NOW + 30_000L, NOW);
    }

    @Test
    void expiredAndroidRetentionNeverCreatesOrReplaysAnotherCommand() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        MessageCommandRecoveryPort recovery = mock(MessageCommandRecoveryPort.class);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkUnknownResultRecoveryService service =
                new HyperlinkUnknownResultRecoveryService(
                        recipients, recovery, dispatchGuard, CLOCK);
        long expired = NOW - 29L * 24 * 60 * 60 * 1_000 - 1;
        HyperlinkReconciliationCandidate candidate =
                new HyperlinkReconciliationCandidate(
                        7L, 11L, 13L, 17L, "hl:7:11:13", 2, expired);

        service.recover(candidate);

        verify(recovery, never()).replay(7L, "hl:7:11:13", NOW);
        verify(dispatchGuard).renew(17L, "hl:7:11:13");
        verify(recipients).scheduleReconciliation(
                "hl:7:11:13", NOW + 24L * 60 * 60 * 1_000, NOW);
    }
}
