package com.armada.contact.task;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.scheduler.ContactTaskLifecycleWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务生命周期推进的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskLifecycleWorkerTest {

    private static final long NOW = 1_700_000_000_000L;

    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskLifecycleWorker worker() {
        return new ContactTaskLifecycleWorker(
                taskMapper, accountMapper, recipientMapper,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void promotesDueScheduledTaskToRunning() {
        when(taskMapper.startDueScheduledTask(eq(1L), anyLong())).thenReturn(1);

        worker().startDueScheduledTask(5L, 1L);

        verify(taskMapper).startDueScheduledTask(1L, NOW);
    }

    @Test
    void settlesAccountsBeforeCompletingTask() {
        when(recipientMapper.countUnfinished(1L)).thenReturn(0L);

        worker().completeDrainedTask(5L, 1L);

        verify(accountMapper).settleDrainedAccounts(1L, NOW);
        verify(taskMapper).completeDrainedTask(1L, NOW);
    }

    @Test
    void doesNotCompleteTaskThatStillHasWork() {
        when(recipientMapper.countUnfinished(1L)).thenReturn(2L);

        worker().completeDrainedTask(5L, 1L);

        verify(taskMapper, never()).completeDrainedTask(anyLong(), anyLong());
    }
}
