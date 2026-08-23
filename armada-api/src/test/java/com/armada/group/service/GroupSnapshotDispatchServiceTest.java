package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.platform.protocol.model.command.ProtocolGroupSnapshotCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群快照任务与 Outbox 同事务关联测试。 */
@ExtendWith(MockitoExtension.class)
class GroupSnapshotDispatchServiceTest {

    @Mock private GroupMetadataSyncTaskService taskService;
    @Mock private GroupMetadataSyncTaskMapper taskMapper;
    @Mock private ProtocolCommandOutboxService outboxService;

    @Test
    void dispatchMetadataTask_claimsThenPersistsCommandCorrelation() {
        GroupMetadataSyncTask task = task();
        GroupExecutionAccount account = new GroupExecutionAccount(
                100L, "ANDROID", "acc-100", "919000000100", false);
        when(taskService.claim(eq(task), eq(account), eq(1_000L), eq(121_000L), any()))
                .thenReturn(true);
        when(outboxService.enqueueGroupSnapshotCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd-1"), 1));
        when(taskMapper.markAwaitingResult(any(), anyInt())).thenReturn(1);
        GroupSnapshotDispatchService service = new GroupSnapshotDispatchService(
                taskService, taskMapper, outboxService, new GroupSnapshotMetrics());

        boolean dispatched = service.dispatchMetadataTask(
                task, account, 1_000L, 121_000L, new GroupMetadataSyncLimits(3, 1));

        assertThat(dispatched).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupSnapshotCommandRequest>> commandCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueGroupSnapshotCommands(commandCaptor.capture());
        ProtocolGroupSnapshotCommandRequest command = commandCaptor.getValue().get(0);
        assertThat(command.scopes()).containsExactly("METADATA", "INVITE_CODE");
        assertThat(command.wsPhone()).isEqualTo("919000000100");
        assertThat(command.protocolBackend().name()).isEqualTo("ANDROID");
        assertThat(command.taskType()).isEqualTo("GROUP_METADATA_SYNC");
        assertThat(command.taskId()).isEqualTo(91L);
        ArgumentCaptor<GroupMetadataSyncTask> taskCaptor =
                ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(taskMapper).markAwaitingResult(taskCaptor.capture(), anyInt());
        assertThat(taskCaptor.getValue().getCurrentCommandId()).isEqualTo("cmd-1");
        assertThat(taskCaptor.getValue().getRequestedScopeMask()).isEqualTo(3);
        assertThat(taskCaptor.getValue().getResultDeadlineAt()).isEqualTo(121_000L);
    }

    @Test
    void dispatchMetadataTask_doesNotWriteOutboxWhenClaimLosesRace() {
        GroupMetadataSyncTask task = task();
        GroupExecutionAccount account = new GroupExecutionAccount(
                100L, "WEB", "acc-100", "919000000100", true);
        when(taskService.claim(eq(task), eq(account), eq(1_000L), eq(121_000L), any()))
                .thenReturn(false);
        GroupSnapshotDispatchService service = new GroupSnapshotDispatchService(
                taskService, taskMapper, outboxService, new GroupSnapshotMetrics());

        assertThat(service.dispatchMetadataTask(
                task, account, 1_000L, 121_000L, new GroupMetadataSyncLimits(3, 1))).isFalse();
        org.mockito.Mockito.verifyNoInteractions(outboxService);
        verify(taskMapper, never()).markAwaitingResult(any(), anyInt());
    }

    @Test
    void dispatchInviteOnlyTaskDoesNotRequestMetadataAgain() {
        GroupMetadataSyncTask task = task();
        task.setCompletedScopeMask(GroupSnapshotDispatchService.SCOPE_METADATA);
        GroupExecutionAccount account = new GroupExecutionAccount(
                100L, "WEB", "acc-100", "919000000100", true);
        when(taskService.claim(eq(task), eq(account), eq(1_000L), eq(121_000L), any()))
                .thenReturn(true);
        when(outboxService.enqueueGroupSnapshotCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd-invite"), 1));
        when(taskMapper.markAwaitingResult(any(), anyInt())).thenReturn(1);
        GroupSnapshotDispatchService service = new GroupSnapshotDispatchService(
                taskService, taskMapper, outboxService, new GroupSnapshotMetrics());

        assertThat(service.dispatchMetadataTask(
                task, account, 1_000L, 121_000L, new GroupMetadataSyncLimits(3, 1))).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupSnapshotCommandRequest>> commandCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueGroupSnapshotCommands(commandCaptor.capture());
        assertThat(commandCaptor.getValue().get(0).scopes()).containsExactly("INVITE_CODE");
    }

    private static GroupMetadataSyncTask task() {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setId(91L);
        task.setTenantId(1L);
        task.setGroupLinkId(5001L);
        task.setGroupJid("120363000@g.us");
        task.setTriggerSource(GroupMetadataSyncTrigger.MANUAL_REFRESH.code());
        task.setAttemptCount(0);
        task.setCandidateCursor(0);
        task.setCompletedScopeMask(0);
        return task;
    }
}
