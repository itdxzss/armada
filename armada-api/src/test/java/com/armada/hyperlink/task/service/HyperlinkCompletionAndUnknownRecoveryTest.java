package com.armada.hyperlink.task.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.armada.shared.tenant.TenantContext;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.shared.exception.BusinessException;

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

    private final HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
    private final HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
    private final HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
    private final HyperlinkMetricsProjectionService metrics = mock(HyperlinkMetricsProjectionService.class);
    private final MessageCommandRecoveryPort recovery = mock(MessageCommandRecoveryPort.class);
    private final HyperlinkAccountDispatchGuard guard = mock(HyperlinkAccountDispatchGuard.class);
    private final HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
    private final HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
    private final HyperlinkUnknownResultRecoveryService service =
            new HyperlinkUnknownResultRecoveryService(recipients, recovery, guard, usages, metrics, runtimes, CLOCK);

    @BeforeEach
    void recoveryFacts() {
        TenantContext.set(7L);
        recipient.setId(13L);
        recipient.setTenantId(7L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(17L);
        recipient.setCommandId("hl:7:11:13");
        recipient.setSendStatus(2);
        usage.setId(19L);
        usage.setUsageStatus(1);
        var runtime = new com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime();
        runtime.setRunStatus(1);
        when(runtimes.selectByTaskIdForUpdate(7L, 11L)).thenReturn(runtime);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        org.mockito.Mockito.doAnswer(invocation -> { recipient.setSendStatus(1); return null; })
                .when(metrics).requeueSystemFailure(any());
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13")).thenReturn(recipient);
        when(recipients.applyResult(any())).thenReturn(1);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void unknownReplaysTheExactCommandBeforeTwoMinuteDeadline() {
        service.recover(candidate(NOW - 120_000L + 1));
        verify(recovery).replay(7L, "hl:7:11:13", NOW);
        verify(guard).renew(17L, "hl:7:11:13");
        verify(recipients).scheduleReconciliation("hl:7:11:13", NOW + 30_000L, NOW);
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void expiredAndroidResultRequeuesForAnotherSenderInsteadOfFailing() {
        service.recover(candidate(NOW - 120_000L));
        assertThat(recipient.getSendStatus()).isEqualTo(1);
        assertThat(recipient.getFailCode()).isEqualTo(HyperlinkRecipientStatus.RESULT_TIMEOUT);
        assertThat(recipient.getCommandId()).isEqualTo("hl:7:11:13");
        verify(usages).completeSlot(19L, false, NOW);
        verify(recipients).rememberRejectedSender(recipient);
        verify(metrics).requeueSystemFailure(recipient);
        verify(recipients, never()).applyResult(any());
        verify(guard).releaseAfterCommit(17L, "hl:7:11:13", 11L, 13L);
        verify(recovery, never()).replay(anyLong(), any(), anyLong());
        verify(guard, never()).renew(anyLong(), any());
        verify(recipients, never()).scheduleReconciliation(any(), anyLong(), anyLong());
    }

    @Test
    void bannedAccountGetsTwoMinuteGraceAndReleasesOnlyOnce() {
        usage.setUsageStatus(3);
        usage.setInvalidAt(NOW - 120_000L);
        service.recover(candidate(NOW - 150_000L));
        service.recover(candidate(NOW - 150_000L));
        assertThat(recipient.getFailCode()).isEqualTo(HyperlinkRecipientStatus.BANNED_RESULT_TIMEOUT);
        verify(usages, times(1)).completeSlot(19L, false, NOW);
        verify(guard, times(1)).releaseAfterCommit(17L, "hl:7:11:13", 11L, 13L);
        verify(recovery, never()).replay(anyLong(), any(), anyLong());
    }

    @Test
    void bannedBeforeGraceStillQueriesButNeverPastGeneralDeadline() {
        usage.setUsageStatus(3);
        usage.setInvalidAt(NOW - 30_000L);
        service.recover(candidate(NOW - 119_999L));
        verify(recovery).replay(7L, "hl:7:11:13", NOW);
        service.recover(candidate(NOW - 120_000L));
        verify(usages).completeSlot(19L, false, NOW);
    }

    @Test
    void lateRecoveryCandidateCannotRenewTerminalOrReassignedMessage() {
        recipient.setSendStatus(4);
        service.recover(candidate(1L));
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13")).thenReturn(null);
        service.recover(candidate(1L));
        verify(guard, never()).renew(anyLong(), any());
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void crossTenantCandidateIsRejectedBeforeAnyLocks() {
        TenantContext.set(8L);
        assertThatThrownBy(() -> service.recover(candidate(1L))).isInstanceOf(BusinessException.class);
        verify(usages, never()).selectByTaskAndAccountForUpdate(anyLong(), anyLong());
    }

    private HyperlinkReconciliationCandidate candidate(long startedAt) {
        return new HyperlinkReconciliationCandidate(7L, 11L, 13L, 17L, "hl:7:11:13", 2, startedAt);
    }
}
