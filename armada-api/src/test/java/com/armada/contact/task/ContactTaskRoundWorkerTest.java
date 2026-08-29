package com.armada.contact.task;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.scheduler.ContactTaskRoundWorker;
import com.armada.contact.task.scheduler.ContactTaskSchedulerProperties;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务轮次执行的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskRoundWorkerTest {

    private static final long NOW = 1_700_000_000_000L;

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;
    @Mock
    private AccountFilterSelectionMapper selectionMapper;
    @Mock
    private MessageSendPort messageSendPort;
    @Mock
    private MarketingTemplateFileMapper fileMapper;
    @Mock
    private ContactTaskRoundWorker.DrainedTaskSettler settler;

    private ContactTaskRoundWorker worker() {
        return new ContactTaskRoundWorker(
                taskMapper, accountMapper, recipientMapper, selectionMapper,
                new ContactTaskMessageCommandFactory(fileMapper),
                messageSendPort,
                new ContactTaskSchedulerProperties(),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                new Random(1L),
                settler);
    }

    private static ContactFriendTask runningTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setRunStatus(1);
        task.setCurrentRoundNo(4L);
        task.setConcurrency(2);
        task.setMessageType(1);
        task.setContent("文案");
        task.setMsgIntervalMinSec(new BigDecimal("1.0"));
        task.setMsgIntervalMaxSec(new BigDecimal("1.0"));
        return task;
    }

    private static ContactFriendTaskAccount accountRow(Long id, Long accountId) {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setId(id);
        row.setTaskId(1L);
        row.setAccountId(accountId);
        row.setState(ContactFriendTaskAccount.STATE_PENDING);
        return row;
    }

    private static ContactFriendTaskRecipient recipient(Long id, String phone) {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(id);
        row.setTaskId(1L);
        row.setTaskAccountId(101L);
        row.setContactPhone(phone);
        row.setContactJid(phone + "@s.whatsapp.net");
        row.setSendStatus(ContactFriendTaskRecipient.STATUS_PENDING);
        return row;
    }

    private void givenOneAccountWithOneRecipient() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of(101L));
        when(accountMapper.selectById(101L)).thenReturn(accountRow(101L, 11L));
        when(selectionMapper.selectSendableByIds(any(), anyInt(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(recipientMapper.selectPendingByAccount(eq(101L), anyInt()))
                .thenReturn(List.of(recipient(999L, "8613900000001")));
        when(recipientMapper.claimForSend(eq(999L), anyLong(), anyString(), anyLong())).thenReturn(1);
        when(taskMapper.claimDueRound(eq(1L), anyLong(), anyLong())).thenReturn(1);
    }

    private void givenAllCommandsAccepted() {
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        });
    }

    @Test
    void skipsTaskThatIsNoLongerRunning() {
        ContactFriendTask paused = runningTask();
        paused.setRunStatus(3);
        when(taskMapper.selectById(1L)).thenReturn(paused);

        worker().runRound(5L, 1L);

        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void skipsMissingTask() {
        when(taskMapper.selectById(1L)).thenReturn(null);

        worker().runRound(5L, 1L);

        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void postponesWhenScheduledStartTimeHasNotArrived() {
        ContactFriendTask task = runningTask();
        task.setTaskStartAt(NOW + 60_000L);
        when(taskMapper.selectById(1L)).thenReturn(task);

        worker().runRound(5L, 1L);

        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void completesTaskWhenNothingLeftToSend() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of());
        when(recipientMapper.countUnfinished(1L)).thenReturn(0L);

        worker().runRound(5L, 1L);

        verify(settler).settle(5L, 1L);
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onlyPostponesWhileSendsAreStillInFlight() {
        when(taskMapper.selectById(1L)).thenReturn(runningTask());
        when(recipientMapper.selectAccountIdsWithPending(eq(1L), anyInt())).thenReturn(List.of());
        when(recipientMapper.countUnfinished(1L)).thenReturn(3L);

        worker().runRound(5L, 1L);

        verify(settler, never()).settle(anyLong(), anyLong());
        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
    }

    @Test
    void postponesWithoutClaimingRoundWhenBacklogIsHigh() {
        givenOneAccountWithOneRecipient();
        // 计划 1 账号 × 20 条 = 20；backlogMultiplier 默认 2 → 阈值 40
        when(recipientMapper.countInFlight(1L)).thenReturn(100L);

        worker().runRound(5L, 1L);

        verify(taskMapper).postponeDueRound(eq(1L), anyLong(), anyLong());
        verify(taskMapper, never()).claimDueRound(anyLong(), anyLong(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void abortsWhenAnotherThreadClaimedTheRound() {
        givenOneAccountWithOneRecipient();
        when(taskMapper.claimDueRound(eq(1L), anyLong(), anyLong())).thenReturn(0);

        worker().runRound(5L, 1L);

        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void enqueuesCommandCarryingIncrementedRoundNumber() {
        givenOneAccountWithOneRecipient();
        givenAllCommandsAccepted();

        worker().runRound(5L, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(captor.capture());
        MessageSendCommand command = captor.getValue().get(0);
        assertThat(command.correlation().contactTask().roundNo()).isEqualTo(5L);
        assertThat(command.correlation().contactTask().recipientId()).isEqualTo(999L);
        assertThat(command.correlation().contactTask().taskAccountId()).isEqualTo(101L);
        assertThat(command.correlation().contactTask().taskId()).isEqualTo(1L);
        assertThat(command.target().jid()).isEqualTo("8613900000001@s.whatsapp.net");
    }

    @Test
    void reusesClaimedCommandIdInTheOutboxCommand() {
        // 抢批写进 recipient 的 commandId 必须与 outbox 命令一致，否则回执定位不到
        givenOneAccountWithOneRecipient();
        givenAllCommandsAccepted();
        ArgumentCaptor<String> claimedId = ArgumentCaptor.forClass(String.class);

        worker().runRound(5L, 1L);

        verify(recipientMapper).claimForSend(eq(999L), eq(5L), claimedId.capture(), anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(captor.capture());
        assertThat(captor.getValue().get(0).commandId()).isEqualTo(claimedId.getValue());
    }

    @Test
    void skipsRecipientLostToAConcurrentRound() {
        givenOneAccountWithOneRecipient();
        when(recipientMapper.claimForSend(eq(999L), anyLong(), anyString(), anyLong())).thenReturn(0);

        worker().runRound(5L, 1L);

        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void skipsAccountWithoutCurrentProtocolFacts() {
        // 圈号后账号被封或导出，本轮读不到协议事实，不能白白消耗收件人
        givenOneAccountWithOneRecipient();
        when(selectionMapper.selectSendableByIds(any(), anyInt(), anyInt())).thenReturn(List.of());

        worker().runRound(5L, 1L);

        verify(recipientMapper, never()).claimForSend(anyLong(), anyLong(), anyString(), anyLong());
        verify(messageSendPort, never()).enqueue(any());
    }

    @Test
    void convertsLocalRejectionIntoRecipientFailure() {
        givenOneAccountWithOneRecipient();
        when(messageSendPort.enqueue(any())).thenAnswer(invocation -> {
            List<MessageSendCommand> commands = invocation.getArgument(0);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.rejected(
                            command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "本地拒绝"))
                    .toList());
        });
        when(recipientMapper.markFailed(anyLong(), anyString(), anyString(), anyLong())).thenReturn(1);

        worker().runRound(5L, 1L);

        verify(recipientMapper).markFailed(eq(999L), eq("INVALID_ANDROID_BUTTON_CONFIG"),
                anyString(), anyLong());
        verify(accountMapper).incrementFailNum(eq(101L), anyLong());
    }

    @Test
    void marksAccountRunningBeforeSending() {
        givenOneAccountWithOneRecipient();
        givenAllCommandsAccepted();

        worker().runRound(5L, 1L);

        verify(accountMapper).markRunning(eq(101L), anyLong());
    }
}
