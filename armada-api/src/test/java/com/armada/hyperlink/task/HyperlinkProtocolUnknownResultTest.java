package com.armada.hyperlink.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.service.HyperlinkProtocolResultService;
import com.armada.hyperlink.task.service.HyperlinkRecipientStateMachine;
import com.armada.hyperlink.task.service.HyperlinkAccountDispatchGuard;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** UNKNOWN 只安排原 command 对账，不失败、不释放槽、不生成第二次逻辑发送。 */
class HyperlinkProtocolUnknownResultTest {

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void unknownOutcomeKeepsSendingAndSchedulesSameCommandReconciliation() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        recipient.setId(13L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(17L);
        recipient.setDataPackageId(23L);
        recipient.setDataPackageGeneration(2);
        recipient.setRecipientPhoneSnapshot("8613800000000");
        recipient.setCommandId("hl:7:11:13");
        recipient.setSendStatus(2);
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13"))
                .thenReturn(recipient);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(),
                mock(DataPackageRecipientClaimService.class), dispatchGuard,
                mock(AccountOperationRestrictionService.class));

        service.handleSendResultReported(event("UNKNOWN", false));

        InOrder order = inOrder(usages, recipients, dispatchGuard);
        order.verify(usages).selectByTaskAndAccountForUpdate(11L, 17L);
        order.verify(recipients).selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13");
        order.verify(dispatchGuard).renew(17L, "hl:7:11:13");
        order.verify(recipients).scheduleReconciliation("hl:7:11:13", 31_000L, 1_000L);
        verify(recipients, never()).applyResult(any());
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void lateUnknownDoesNotRecreateAHolderForTerminalRecipient() {
        assertTerminalUncertainEventIgnored("UNKNOWN", 3);
    }

    @Test
    void lateNonTerminalFailureDoesNotRecreateAHolderForTerminalRecipient() {
        assertTerminalUncertainEventIgnored("FAILED", 6);
    }

    @Test
    void ackArrivingBeforeSendResultMonotonicallyCompletesTheSameUsageSlot() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        recipient.setId(13L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(17L);
        recipient.setDataPackageId(23L);
        recipient.setDataPackageGeneration(2);
        recipient.setRecipientPhoneSnapshot("8613800000000");
        recipient.setCommandId("hl:7:11:13");
        recipient.setSendStatus(2);
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13"))
                .thenReturn(recipient);
        when(recipients.advanceAck(any(HyperlinkTaskRecipient.class), eq(2))).thenReturn(1);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(),
                data, dispatchGuard, mock(AccountOperationRestrictionService.class));

        service.handleAck(new ProtocolMessageAckEvent("ack1", 7L, "hyperlink_task",
                11L, 13L, "hl:7:11:13", 17L, "web", "acc17",
                "8613800000000@s.whatsapp.net", "PRIVATE", "m1", "READ",
                true, null, null, 2_000L, "worker"));

        verify(recipients).advanceAck(argThat(value -> value.getId() == 13L
                && value.getSendStatus() == 5 && "m1".equals(value.getProtocolMessageId())
                && value.getUpdatedAt() == 2_000L), eq(2));
        verify(usages).completeSlot(19L, true, 2_000L);
        InOrder order = inOrder(usages, recipients, data);
        order.verify(usages).selectByTaskAndAccountForUpdate(11L, 17L);
        order.verify(recipients).selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13");
        order.verify(recipients).advanceAck(any(HyperlinkTaskRecipient.class), eq(2));
        order.verify(usages).completeSlot(19L, true, 2_000L);
        order.verify(data).advanceDeliveryFact(11L, 23L, 2,
                "8613800000000", com.armada.hyperlink.data.model.enums.DataPackagePoolStatus.DELIVERED,
                2_000L);
        verify(dispatchGuard).releaseAfterCommit(17L, "hl:7:11:13", 11L, 13L);
    }

    @Test
    void ackRereadsRecipientAfterUsageLockBeforeCalculatingTransition() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRecipient stale = recipient();
        stale.setCommandId("hl:7:11:13");
        HyperlinkTaskRecipient latest = recipient();
        latest.setCommandId("hl:7:11:13");
        latest.setSendStatus(3);
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(stale);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13"))
                .thenReturn(latest);
        when(recipients.advanceAck(any(HyperlinkTaskRecipient.class), eq(3))).thenReturn(1);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(), data, dispatchGuard,
                mock(AccountOperationRestrictionService.class));

        service.handleAck(new ProtocolMessageAckEvent("ack-after-success", 7L, "hyperlink_task",
                11L, 13L, "hl:7:11:13", 17L, "web", "acc17",
                "8613800000000@s.whatsapp.net", "PRIVATE", "m1", "READ",
                true, null, null, 2_000L, "worker"));

        InOrder order = inOrder(usages, recipients);
        order.verify(usages).selectByTaskAndAccountForUpdate(11L, 17L);
        order.verify(recipients).selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13");
        order.verify(recipients).advanceAck(argThat(value -> value.getSendStatus() == 5), eq(3));
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void concurrentSendResultLoserDoesNotAdvanceUsageOrDataFacts() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipient recipient = recipient();
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.applyResult(any(HyperlinkTaskRecipient.class))).thenReturn(0);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(), data, dispatchGuard,
                mock(AccountOperationRestrictionService.class));

        service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "e2", 7L, null, null, null, null, "acc17", null,
                "hl:7:11:13", true, "m1", null, null, 2_000L, "worker",
                null, null, "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L,
                "SUCCESS", true));

        InOrder order = inOrder(usages, recipients);
        order.verify(usages).selectByTaskAndAccountForUpdate(11L, 17L);
        order.verify(recipients).applyResult(any(HyperlinkTaskRecipient.class));
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
        verify(data, never()).advanceDeliveryFact(anyLong(), anyLong(), anyInt(),
                anyString(), any(), anyLong());
        verify(dispatchGuard, never()).releaseAfterCommit(
                anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void terminalAccountRestrictionStopsAccountAndRequeuesSameMaterialForAnotherAccount() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        HyperlinkTaskRecipient recipient = recipient();
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(recipient);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.requeueAfterAccountRestriction(
                eq(13L), eq("hl:7:11:13"), anyLong())).thenReturn(1);
        when(usages.markOperationRestricted(
                eq(19L), eq(6), any(), any(), anyLong())).thenReturn(1);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        AccountOperationRestrictionService restrictionService =
                mock(AccountOperationRestrictionService.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(), data, dispatchGuard,
                restrictionService);

        service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "restricted", 7L, null, null, null, null, "acc17", null,
                "hl:7:11:13", false, null, "ACCOUNT_REACHOUT_RESTRICTED",
                "reachout restricted", 2_000L, "worker", null, null,
                "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L,
                "FAILED", true));

        verify(restrictionService).restrictMessageSending(
                eq(17L), eq("ACCOUNT_REACHOUT_RESTRICTED"), eq(2_000L), anyLong());
        verify(recipients).requeueAfterAccountRestriction(
                eq(13L), eq("hl:7:11:13"), anyLong());
        verify(usages).completeSlot(eq(19L), eq(false), anyLong());
        verify(usages).markOperationRestricted(eq(19L), eq(6),
                eq("ACCOUNT_REACHOUT_RESTRICTED"), eq("reachout restricted"), anyLong());
        verify(dispatchGuard).releaseAfterCommit(17L, "hl:7:11:13", 11L, 13L);
        verify(recipients, never()).applyResult(any());
        verify(data, never()).advanceDeliveryFact(
                anyLong(), anyLong(), anyInt(), anyString(), any(), anyLong());
    }

    @Test
    void delayedCallbackFromPreviousDispatchAttemptIsIgnored() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRecipient current = recipient();
        current.setCommandId("hl:7:11:13:2");
        when(recipients.selectCurrentByIdentity(7L, 11L, 13L)).thenReturn(current);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(),
                mock(DataPackageRecipientClaimService.class),
                mock(HyperlinkAccountDispatchGuard.class),
                mock(AccountOperationRestrictionService.class));

        service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "old", 7L, null, null, null, null, "acc17", null,
                "hl:7:11:13", false, null, "ACCOUNT_REACHOUT_RESTRICTED",
                "old callback", 2_000L, "worker", null, null,
                "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L,
                "FAILED", true));

        verify(recipients).selectCurrentByIdentity(7L, 11L, 13L);
        verify(usages, never()).selectByTaskAndAccountForUpdate(
                anyLong(), anyLong());
        verify(recipients, never()).applyResult(any());
    }

    private HyperlinkTaskRecipient recipient() {
        HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
        recipient.setId(13L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(17L);
        recipient.setDataPackageId(23L);
        recipient.setDataPackageGeneration(2);
        recipient.setRecipientPhoneSnapshot("8613800000000");
        recipient.setCommandId("hl:7:11:13");
        recipient.setSendStatus(2);
        return recipient;
    }

    private void assertTerminalUncertainEventIgnored(String outcome, int latestStatus) {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRecipient observed = recipient();
        HyperlinkTaskRecipient latest = recipient();
        latest.setSendStatus(latestStatus);
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId("hl:7:11:13")).thenReturn(observed);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13"))
                .thenReturn(latest);
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);
        HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
                recipients, usages, new HyperlinkRecipientStateMachine(),
                mock(DataPackageRecipientClaimService.class), dispatchGuard,
                mock(AccountOperationRestrictionService.class));

        service.handleSendResultReported(event(outcome, false));

        InOrder order = inOrder(usages, recipients);
        order.verify(usages).selectByTaskAndAccountForUpdate(11L, 17L);
        order.verify(recipients).selectByIdentityForUpdate(7L, 11L, 13L, "hl:7:11:13");
        verify(dispatchGuard, never()).renew(anyLong(), anyString());
        verify(recipients, never()).scheduleReconciliation(anyString(), anyLong(), anyLong());
        verify(recipients, never()).applyResult(any());
    }

    private ProtocolMessageSendResultReportedEvent event(String outcome, boolean terminal) {
        return new ProtocolMessageSendResultReportedEvent("e1", 7L, null, null, null, null,
                "acc17", null, "hl:7:11:13", false, null,
                "MESSAGE_SEND_RESULT_UNKNOWN", "result uncertain", 1_000L, "worker",
                null, null, "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L, outcome, terminal);
    }
}
