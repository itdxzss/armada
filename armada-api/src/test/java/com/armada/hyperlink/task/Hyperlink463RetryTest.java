package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.service.HyperlinkProtocolResultService;
import com.armada.hyperlink.task.service.HyperlinkMetricsProjectionService;
import com.armada.hyperlink.task.service.HyperlinkAccountDispatchGuard;
import com.armada.hyperlink.task.service.HyperlinkRecipientStateMachine;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** 两条回执入口始终换未拒绝过的发信人，不因累计尝试次数终结目标。 */
class Hyperlink463RetryTest {
    @ParameterizedTest
    @CsvSource({"1,false", "2,false", "3,false", "4,false", "5,false", "10,false", "1,true", "3,true", "4,true", "10,true"})
    void keepsRecipientPendingForAnotherSenderAtAnyAttempt(int attempt, boolean ack) {
        var recipients = mock(HyperlinkTaskRecipientMapper.class);
        var usages = mock(HyperlinkTaskAccountUsageMapper.class);
        var metrics = mock(HyperlinkMetricsProjectionService.class);
        var data = mock(DataPackageRecipientClaimService.class);
        var guard = mock(HyperlinkAccountDispatchGuard.class);
        var restrictions = mock(AccountOperationRestrictionService.class);
        var row = new HyperlinkTaskRecipient();
        row.setId(13L); row.setTenantId(7L); row.setHyperlinkTaskId(11L);
        row.setAccountId(17L); row.setCommandId("hl:7:11:13:" + attempt);
        row.setSendStatus(2); row.setDispatchAttempt(attempt);
        row.setDataPackageId(23L); row.setDataPackageGeneration(2);
        row.setRecipientPhoneSnapshot("551234567890");
        var usage = new HyperlinkTaskAccountUsage(); usage.setId(19L);
        when(recipients.selectByCommandId(row.getCommandId())).thenReturn(row);
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, row.getCommandId())).thenReturn(row);
        when(usages.selectByTaskAndAccountForUpdate(11L, 17L)).thenReturn(usage);
        when(recipients.applyResult(row)).thenReturn(1);
        var service = new HyperlinkProtocolResultService(recipients, usages,
                new HyperlinkRecipientStateMachine(), data, guard, restrictions, metrics);
        Runnable deliver = () -> {
            if (ack) {
                service.handleAck(new ProtocolMessageAckEvent("ack", 7L, "hyperlink_task", 11L, 13L,
                        row.getCommandId(), 17L, "android", "acc17", "551234567890@s.whatsapp.net",
                        "PRIVATE", "msg", "FAILED", false, "WA_ACK_REJECTED_463", "463", 1000L, "worker"));
            } else {
                service.handleSendResultReported(new ProtocolMessageSendResultReportedEvent("e", 7L,
                        null, null, null, null, "acc17", null, row.getCommandId(), false, "msg",
                        "WA_ACK_REJECTED_463", "463", 1000L, "worker", null, null, "hyperlink_task",
                        null, null, null, null, null, "551234567890@s.whatsapp.net", "PRIVATE",
                        11L, 13L, "FAILED", true));
            }
        };
        deliver.run();
        verify(recipients).rememberRejectedSender(row);
        verify(usages).completeSlot(eq(19L), eq(false), anyLong());
        verify(guard).releaseAfterCommit(17L, row.getCommandId(), 11L, 13L);
        verifyNoInteractions(restrictions);
        verify(usages, never()).markOperationRestricted(anyLong(), anyInt(), anyString(), anyString(), anyLong());
        verify(usages, never()).scheduleNextSend(anyLong(), anyLong(), anyLong());
        verify(metrics).requeueSystemFailure(row);
        verify(recipients, never()).applyResult(any());
        verifyNoInteractions(data);
        assertThat(row.getNextDispatchAt()).isBetween(System.currentTimeMillis() - 1000,
                System.currentTimeMillis() + 120_000L);
        // The committed requeue cleared this command; a duplicate result cannot consume another slot.
        when(recipients.selectByIdentityForUpdate(7L, 11L, 13L, row.getCommandId())).thenReturn(null);
        deliver.run();
        verify(usages, times(1)).completeSlot(eq(19L), eq(false), anyLong());
        verify(recipients, times(1)).rememberRejectedSender(row);
    }

    @Test
    void reasonIsVisibleButDisappearsAfterSuccess() {
        assertThat(HyperlinkRecipientStatus.FAILED.businessMessage("WA_ACK_REJECTED_463"))
                .isEqualTo("WhatsApp 拒绝发送");
        assertThat(HyperlinkRecipientStatus.PENDING.businessMessage("WA_ACK_REJECTED_463"))
                .contains("其他发信人", "等待资源").doesNotContain("暂停");
        assertThat(HyperlinkRecipientStatus.SUCCESS.businessMessage("WA_ACK_REJECTED_463")).isNull();
    }
}
