package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务发送结果回写的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskSendResultSinkTest {

    private static final long NOW = 2_000L;

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskSendResultSink sink() {
        return new ContactTaskSendResultSink(
                taskMapper, accountMapper, recipientMapper, () -> NOW);
    }

    private static ProtocolMessageSendResultReportedEvent event(boolean success, String source) {
        return new ProtocolMessageSendResultReportedEvent(
                "evt_1", 5L,
                null, null, null, 7L,
                "acc_1", "8613900000001@s.whatsapp.net", "cmd_1",
                success, success ? "wamid.ABC" : null,
                success ? null : "TIMEOUT", success ? null : "发送超时",
                1_999L, "worker-1",
                null, null, source,
                "UNCONFIRMED", "PRECHECK_SKIPPED_BY_PEER_TARGET", 1_998L,
                null, null,
                1L, 101L, 999L);
    }

    private static ContactFriendTaskRecipient recipient(int attemptCount) {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(999L);
        row.setTaskId(1L);
        row.setTaskAccountId(101L);
        row.setAttemptCount(attemptCount);
        return row;
    }

    private static ContactFriendTask taskWithRetryMax(int retryMax) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setRetryMax(retryMax);
        return task;
    }

    @Test
    void claimsOnlyContactTaskSource() {
        assertThat(sink().supports(event(true, "contact_task"))).isTrue();
        assertThat(sink().supports(event(true, "marketing_task"))).isFalse();
        assertThat(sink().supports(event(true, "group_creation_marketing"))).isFalse();
        assertThat(sink().supports(null)).isFalse();
    }

    @Test
    void writesBackAllThreeLevelsOnSuccess() {
        when(recipientMapper.markSuccess(eq(999L), eq("wamid.ABC"), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(true, "contact_task"));

        verify(recipientMapper).markSuccess(999L, "wamid.ABC", NOW);
        verify(accountMapper).incrementSentNum(eq(101L), anyLong());
        verify(taskMapper).incrementSuccessMessageNum(eq(1L), eq(1), anyLong());
    }

    @Test
    void ignoresDuplicateSuccessReport() {
        // 条件更新返回 0 = 这条已经落过终态，计数一律不能再动
        when(recipientMapper.markSuccess(anyLong(), anyString(), anyLong())).thenReturn(0);

        sink().handleSendResultReported(event(true, "contact_task"));

        verify(accountMapper, never()).incrementSentNum(anyLong(), anyLong());
        verify(taskMapper, never()).incrementSuccessMessageNum(anyLong(), anyInt(), anyLong());
    }

    @Test
    void requeuesFailureWhileRetriesRemain() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(1));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markRetry(eq(999L), eq("TIMEOUT"), anyString(), anyLong());
        verify(recipientMapper, never()).markFailed(anyLong(), anyString(), anyString(), anyLong());
        verify(accountMapper, never()).incrementFailNum(anyLong(), anyLong());
    }

    @Test
    void terminatesFailureWhenRetryBudgetIsSpent() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(3));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));
        when(recipientMapper.markFailed(eq(999L), anyString(), anyString(), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markFailed(eq(999L), eq("TIMEOUT"), anyString(), anyLong());
        verify(accountMapper).incrementFailNum(eq(101L), anyLong());
    }

    @Test
    void treatsZeroRetryMaxAsNoRetry() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(1));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(0));
        when(recipientMapper.markFailed(anyLong(), anyString(), anyString(), anyLong())).thenReturn(1);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper).markFailed(eq(999L), anyString(), anyString(), anyLong());
        verify(recipientMapper, never()).markRetry(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void ignoresDuplicateFailureReport() {
        when(recipientMapper.selectById(999L)).thenReturn(recipient(3));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(3));
        when(recipientMapper.markFailed(anyLong(), anyString(), anyString(), anyLong())).thenReturn(0);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(accountMapper, never()).incrementFailNum(anyLong(), anyLong());
    }

    @Test
    void ignoresEventForUnknownRecipient() {
        when(recipientMapper.selectById(999L)).thenReturn(null);

        sink().handleSendResultReported(event(false, "contact_task"));

        verify(recipientMapper, never()).markRetry(anyLong(), anyString(), anyString(), anyLong());
        verify(recipientMapper, never()).markFailed(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void truncatesOverlongFailureDescription() {
        // error_desc 是 VARCHAR(255)，协议层长文案不截断会写爆列宽
        when(recipientMapper.selectById(999L)).thenReturn(recipient(3));
        when(taskMapper.selectById(1L)).thenReturn(taskWithRetryMax(0));
        ProtocolMessageSendResultReportedEvent longReason =
                new ProtocolMessageSendResultReportedEvent(
                        "evt_1", 5L, null, null, null, 7L,
                        "acc_1", "8613900000001@s.whatsapp.net", "cmd_1",
                        false, null, "TIMEOUT", "x".repeat(500),
                        1_999L, "worker-1", null, null, "contact_task",
                        null, null, null, null, null,
                        1L, 101L, 999L);

        sink().handleSendResultReported(longReason);

        verify(recipientMapper).markFailed(eq(999L), eq("TIMEOUT"),
                eq("x".repeat(255)), anyLong());
    }
}
