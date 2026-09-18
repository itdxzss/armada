package com.armada.hyperlink.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.service.HyperlinkAccountDispatchGuard;
import com.armada.hyperlink.task.service.HyperlinkProtocolResultService;
import com.armada.hyperlink.task.service.HyperlinkRecipientStateMachine;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Android 旧事件缺少 outcome/terminal 时，不把未知结果误写为最终失败。 */
class HyperlinkLegacyUnknownResultTest {
    private static final String COMMAND_ID = "hl:7:11:13";
    private final HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
    private final HyperlinkTaskAccountUsageMapper usages = mock(HyperlinkTaskAccountUsageMapper.class);
    private final DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
    private final HyperlinkAccountDispatchGuard guard = mock(HyperlinkAccountDispatchGuard.class);
    private final AccountOperationRestrictionService restrictions =
            mock(AccountOperationRestrictionService.class);
    private final HyperlinkTaskRecipient recipient = new HyperlinkTaskRecipient();
    private final HyperlinkProtocolResultService service = new HyperlinkProtocolResultService(
            recipients, usages, new HyperlinkRecipientStateMachine(), data, guard, restrictions);

    @BeforeEach
    void setUp() {
        recipient.setId(13L);
        recipient.setHyperlinkTaskId(11L);
        recipient.setAccountId(17L);
        recipient.setDataPackageId(23L);
        recipient.setDataPackageGeneration(2);
        recipient.setRecipientPhoneSnapshot("8613800000000");
        recipient.setCommandId(COMMAND_ID);
        recipient.setSendStatus(2);
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(19L);
        when(recipients.selectByCommandId(COMMAND_ID)).thenReturn(recipient);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, COMMAND_ID))
                .thenReturn(recipient);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void legacyUnknownKeepsOriginalSendAndDoesNotConsumeTargetAsFailure() {
        TenantContext.set(99L);

        service.handleSendResultReported(legacyEvent(false, "SEND_RESULT_UNKNOWN"));

        assertEquals(2, recipient.getSendStatus());
        assertEquals(COMMAND_ID, recipient.getCommandId());
        assertEquals(17L, recipient.getAccountId());
        assertEquals(99L, TenantContext.get());
        verify(guard).renew(17L, COMMAND_ID);
        verify(recipients).scheduleReconciliation(COMMAND_ID, 31_000L, 1_000L);
        verify(recipients, never()).applyResult(any());
        verify(recipients, never()).requeueAfterSystemFailure(any());
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
        verify(guard, never()).releaseAfterCommit(anyLong(), anyString(), anyLong(), anyLong());
        verifyNoInteractions(data, restrictions);
    }

    @Test
    void deliveryAfterLegacyUnknownCanCompleteOriginalRecipient() {
        when(recipients.advanceAck(any(), eq(2))).thenReturn(1);

        service.handleSendResultReported(legacyEvent(false, "SEND_RESULT_UNKNOWN"));
        service.handleAck(new ProtocolMessageAckEvent("ack1", 7L, "hyperlink_task",
                11L, 13L, COMMAND_ID, 17L, "ANDROID", "acc17",
                "8613800000000@s.whatsapp.net", "PRIVATE", "m1", "DELIVERED",
                true, null, null, 2_000L, "worker"));

        assertEquals(4, recipient.getSendStatus());
        verify(recipients).advanceAck(argThat(row -> row.getSendStatus() == 4
                && "m1".equals(row.getProtocolMessageId())), eq(2));
        verify(usages).completeSlot(19L, true, 2_000L);
        verify(data).advanceDeliveryFact(11L, 23L, 2, "8613800000000",
                DataPackagePoolStatus.DELIVERED, 2_000L);
        verify(guard).releaseAfterCommit(17L, COMMAND_ID, 11L, 13L);
        verify(recipients, never()).applyResult(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7})
    void lateLegacyUnknownLeavesExistingOutcomeUntouched(int status) {
        recipient.setSendStatus(status);

        service.handleSendResultReported(legacyEvent(false, "SEND_RESULT_UNKNOWN"));

        assertEquals(status, recipient.getSendStatus());
        verify(recipients, never()).applyResult(any());
        verify(recipients, never()).scheduleReconciliation(anyString(), anyLong(), anyLong());
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
        verifyNoInteractions(guard, data, restrictions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEND_FAILED", "ACCOUNT_BANNED"})
    void legacyNativeFailureAndBannedCannotProveNotSent(String code) {
        service.handleSendResultReported(legacyEvent(false, code));
        assertEquals(2, recipient.getSendStatus());
        verify(recipients).scheduleReconciliation(COMMAND_ID, 31_000L, 1_000L);
        verifyNoInteractions(data);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNT_OFFLINE", "SEND_PREPARE_FAILED", "RECIPIENT_SESSION_UNAVAILABLE", "LID_TARGET_CIPHERTEXT_MISSING"})
    void knownPreparationFailureRequeuesWithoutConsumingTarget(String code) {
        recipient.setDispatchAttempt(1);
        when(recipients.requeueAfterSystemFailure(any())).thenReturn(1);
        service.handleSendResultReported(legacyEvent(false, code));
        verify(recipients).requeueAfterSystemFailure(argThat(row -> code.equals(row.getFailCode())
                && row.getNextDispatchAt() > row.getUpdatedAt()));
        verify(usages).completeSlot(eq(19L), eq(false), anyLong());
        verify(guard).releaseAfterCommit(17L, COMMAND_ID, 11L, 13L);
        verify(recipients, never()).applyResult(any());
        verifyNoInteractions(data, restrictions);
    }

    @Test void duplicateRecoveryDoesNotReleaseCapacityTwice() {
        when(recipients.requeueAfterSystemFailure(any())).thenReturn(0);
        service.handleSendResultReported(legacyEvent(false, "ACCOUNT_OFFLINE"));
        verify(usages, never()).completeSlot(anyLong(), anyBoolean(), anyLong());
        verify(recipients, never()).applyResult(any());
        verifyNoInteractions(data, guard);
    }

    @Test
    void legacySuccessKeepsExistingBehavior() {
        when(recipients.applyResult(any())).thenReturn(1);

        service.handleSendResultReported(legacyEvent(true, null));

        assertEquals(3, recipient.getSendStatus());
        verify(usages).completeSlot(19L, true, 1_000L);
        verify(data).advanceDeliveryFact(11L, 23L, 2, "8613800000000",
                DataPackagePoolStatus.SENT, 1_000L);
        verify(recipients, never()).scheduleReconciliation(anyString(), anyLong(), anyLong());
    }

    @Test
    void confirmedNotSentBannedAccountRequeuesAndKeepsBanStatistics() {
        recipient.setDispatchAttempt(1);
        when(recipients.requeueAfterSystemFailure(any())).thenReturn(1);
        service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent(
                "e2", 7L, null, null, null, null, "acc17", null, COMMAND_ID, false, null,
                "ACCOUNT_BANNED", "banned before dispatch", 1_000L, "worker", null, null,
                "hyperlink_task", null, null, null, null, null,
                "8613800000000@s.whatsapp.net", "PRIVATE", 11L, 13L, "NOT_SENT", true));
        verify(recipients).requeueAfterSystemFailure(argThat(row ->
                row.getNextDispatchAt() != Long.MAX_VALUE));
        verify(usages).markInvalid(eq(19L), eq(3), eq("ACCOUNT_BANNED"), anyString(), anyLong());
        verify(usages).completeSlot(eq(19L), eq(false), anyLong());
        verifyNoInteractions(data, restrictions);
    }

    private ProtocolMessageSendResultReportedEvent legacyEvent(boolean success, String code) {
        return new ProtocolMessageSendResultReportedEvent("e1", 7L, null, null, null, null,
                "acc17", null, COMMAND_ID, success, success ? "m1" : null,
                code, code, 1_000L, "worker", null, null, "hyperlink_task",
                null, null, null, null, null, "8613800000000@s.whatsapp.net", "PRIVATE",
                11L, 13L, null, null);
    }
}
