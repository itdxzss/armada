package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.AccountGroupMembershipStatusService;
import com.armada.group.service.GroupSnapshotDispatchService;
import com.armada.group.service.GroupBatchSnapshotDispatchService;
import com.armada.group.service.GroupSnapshotProperties;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.platform.kafka.consumer.group.ProtocolGroupSnapshotResultReportedEvent;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** currentCommandId、scope 事实门槛与候选轮换测试。 */
@ExtendWith(MockitoExtension.class)
class GroupSnapshotResultReportedSinkAdapterTest {

    @Mock private GroupMetadataSyncTaskMapper taskMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountGroupMembershipStatusService membershipStatusService;
    @Mock private GroupBatchTaskItemMapper batchItemMapper;
    @Mock private GroupBatchTaskMapper batchTaskMapper;
    @Mock private GroupExecutionAccountSelector selector;
    @Mock private GroupSnapshotDispatchService dispatchService;
    @Mock private GroupBatchSnapshotDispatchService batchDispatchService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    private void stubAccountBinding() {
        Account account = new Account();
        account.setId(100L);
        account.setTenantId(1L);
        account.setOwnerUserId(501L);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc-100");
        org.mockito.Mockito.lenient()
                .when(accountMapper.selectActiveByProtocolAccountId("acc-100"))
                .thenReturn(account);
    }

    @Test
    void successRequiresFactsThenSettlesCurrentCommand() {
        GroupMetadataSyncTask task = task(3, 3, 0);
        when(taskMapper.selectByCurrentCommandId(1L, "cmd-1")).thenReturn(task);
        when(taskMapper.settleCurrentCommand(any(), eq(GroupMetadataSyncStatus.RUNNING.code())))
                .thenReturn(1);
        adapter().handleSnapshotResult(event(Map.of(
                "METADATA", success(900L), "INVITE_CODE", success(950L))));

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(taskMapper).settleCurrentCommand(captor.capture(), eq(GroupMetadataSyncStatus.RUNNING.code()));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus())
                .isEqualTo(GroupMetadataSyncStatus.SUCCEEDED.code());
    }

    @Test
    void successWithRerunRequestedStartsFreshPendingCycle() {
        GroupMetadataSyncTask task = task(3, 3, 2);
        task.setRerunRequested(true);
        when(taskMapper.selectByCurrentCommandId(1L, "cmd-1")).thenReturn(task);
        when(taskMapper.settleCurrentCommand(any(), eq(GroupMetadataSyncStatus.RUNNING.code())))
                .thenReturn(1);

        adapter().handleSnapshotResult(event(3, Map.of(
                "METADATA", success(2_000L), "INVITE_CODE", success(2_100L))));

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(taskMapper).settleCurrentCommand(captor.capture(), eq(GroupMetadataSyncStatus.RUNNING.code()));
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .extracting(
                        GroupMetadataSyncTask::getStatus,
                        GroupMetadataSyncTask::getCompletedScopeMask,
                        GroupMetadataSyncTask::getCandidateCursor)
                .containsExactly(GroupMetadataSyncStatus.PENDING.code(), 0, 0);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getNextRunAt()).isNotNull();
    }

    @Test
    void inviteFailureKeepsMetadataAndDispatchesNextCandidateInSameCall() {
        GroupMetadataSyncTask task = task(3, 1, 0);
        when(taskMapper.selectByCurrentCommandId(1L, "cmd-1")).thenReturn(task);
        GroupExecutionAccount next = new GroupExecutionAccount(101L, "WEB", "acc-101", "9191", true);
        when(selector.find(5001L, 1)).thenReturn(Optional.of(next));
        when(taskMapper.resetCurrentCommandForRetry(any(), anyInt(), anyInt())).thenReturn(1);
        when(dispatchService.dispatchMetadataTask(
                eq(task), eq(next), anyLong(), anyLong(), any(),
                eq("INVITE_CANDIDATE_ROTATION"))).thenReturn(true);

        adapter().handleSnapshotResult(event(Map.of(
                "METADATA", success(2_000L),
                "INVITE_CODE", failed(2_100L, "GROUP_PERMISSION_DENIED"))));

        verify(taskMapper).resetCurrentCommandForRetry(
                any(), eq(GroupMetadataSyncStatus.RUNNING.code()), eq(GroupMetadataSyncStatus.PENDING.code()));
        verify(dispatchService).dispatchMetadataTask(
                eq(task), eq(next), anyLong(), anyLong(), any(),
                eq("INVITE_CANDIDATE_ROTATION"));
    }

    @Test
    void groupNotJoinedCalibratesOnlyCommandAccountBeforeRotating() {
        GroupMetadataSyncTask task = task(1, 0, 0);
        when(taskMapper.selectByCurrentCommandId(1L, "cmd-1")).thenReturn(task);
        GroupExecutionAccount next = new GroupExecutionAccount(101L, "WEB", "acc-101", "9191", true);
        when(selector.find(5001L, 1)).thenReturn(Optional.of(next));
        when(taskMapper.resetCurrentCommandForRetry(any(), anyInt(), anyInt())).thenReturn(1);
        when(dispatchService.dispatchMetadataTask(
                eq(task), eq(next), anyLong(), anyLong(), any(),
                eq("INVITE_CANDIDATE_ROTATION"))).thenReturn(true);

        adapter().handleSnapshotResult(event(Map.of(
                "METADATA", failed(2_200L, "GROUP_NOT_JOINED"))));

        ArgumentCaptor<com.armada.group.model.dto.AccountGroupMembershipChangedEvent> captor =
                ArgumentCaptor.forClass(com.armada.group.model.dto.AccountGroupMembershipChangedEvent.class);
        verify(membershipStatusService).applyMembershipChanged(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .extracting(
                        com.armada.group.model.dto.AccountGroupMembershipChangedEvent::accountId,
                        com.armada.group.model.dto.AccountGroupMembershipChangedEvent::groupJid,
                        com.armada.group.model.dto.AccountGroupMembershipChangedEvent::action,
                        com.armada.group.model.dto.AccountGroupMembershipChangedEvent::occurredAt,
                        com.armada.group.model.dto.AccountGroupMembershipChangedEvent::source)
                .containsExactly(100L, "120363000@g.us", "remove", 2_200L,
                        AccountGroupMembershipStatusServiceImpl.GROUP_SNAPSHOT_NOT_JOINED_SOURCE);
    }

    @Test
    void successfulScopeWithoutFactIsRejectedForKafkaRetry() {
        when(taskMapper.selectByCurrentCommandId(1L, "cmd-1")).thenReturn(task(3, 0, 0));
        assertThatThrownBy(() -> adapter().handleSnapshotResult(event(Map.of(
                "METADATA", success(2_000L), "INVITE_CODE", success(2_100L)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("事实尚未完成落库");
    }

    @Test
    void invalidPayloadSettlementFailsCurrentCommandWithoutNormalCorrelationChecks() {
        GroupMetadataSyncTask task = task(3, 0, 0);
        when(taskMapper.selectByCurrentCommandIdUnscoped("cmd-1")).thenReturn(task);

        adapter().handleSnapshotResult(new ProtocolGroupSnapshotResultReportedEvent(
                "evt-invalid", 0L, 0L, "acc-100", "WEB", 0L,
                "bad-jid", "GROUP_METADATA_SYNC", 9001L, 1,
                "cmd-1", Map.of("UNKNOWN", failed(2_000L, "INVALID_PAYLOAD")), "worker-1"));

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(taskMapper).settleCurrentCommand(captor.capture(), eq(GroupMetadataSyncStatus.RUNNING.code()));
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .extracting(GroupMetadataSyncTask::getStatus, GroupMetadataSyncTask::getLastErrorCode)
                .containsExactly(GroupMetadataSyncStatus.FAILED.code(), "INVALID_PAYLOAD");
        verify(accountMapper, never()).selectActiveByProtocolAccountId(any());
        verify(selector, never()).find(anyLong(), anyInt());
    }

    @Test
    void batchMetadataSuccessSettlesItemAndIncrementsParentExactlyOnce() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(19L);
        item.setTenantId(1L);
        item.setTaskId(900L);
        item.setGroupLinkId(5001L);
        item.setGroupJid("120363000@g.us");
        item.setAccountId(100L);
        item.setStatus(GroupBatchTaskItemStatus.WAITING_RESULT.code());
        item.setCurrentCommandId("cmd-batch");
        item.setAttemptCount(1);
        item.setCompletedScopeMask(1);
        item.setUpdatedAt(1_000L);
        GroupBatchTask batch = new GroupBatchTask();
        batch.setId(900L);
        batch.setOwnerUserId(501L);
        batch.setTaskType(GroupBatchTaskType.REFRESH_INFO.code());
        batch.setStatus(GroupBatchTaskStatus.RUNNING.code());
        when(batchItemMapper.selectByCurrentCommandId(1L, "cmd-batch")).thenReturn(item);
        when(batchTaskMapper.selectByIdForExecution(900L)).thenReturn(batch);
        when(batchItemMapper.settleCurrentCommand(
                any(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()))).thenReturn(1);

        adapter().handleSnapshotResult(new ProtocolGroupSnapshotResultReportedEvent(
                "evt-batch", 1L, 100L, "acc-100", "WEB", 5001L,
                "120363000@g.us", "GROUP_BATCH_TASK_ITEM", 19L, 1,
                "cmd-batch", Map.of("METADATA", success(900L)), "worker-1"));

        verify(batchItemMapper).settleCurrentCommand(
                any(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()));
        verify(batchTaskMapper).applyItemOutcome(
                eq(900L), eq(true), eq(GroupBatchTaskStatus.COMPLETED.code()),
                eq(GroupBatchTaskStatus.RUNNING.code()), anyLong());
    }

    @Test
    void unavailableInviteLinkSettlesBatchWithFriendlyReason() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(20L);
        item.setTenantId(1L);
        item.setTaskId(901L);
        item.setGroupLinkId(5001L);
        item.setGroupJid("120363000@g.us");
        item.setAccountId(100L);
        item.setStatus(GroupBatchTaskItemStatus.WAITING_RESULT.code());
        item.setCurrentCommandId("cmd-invite");
        item.setAttemptCount(1);
        item.setCompletedScopeMask(0);
        item.setUpdatedAt(1_000L);
        GroupBatchTask batch = new GroupBatchTask();
        batch.setId(901L);
        batch.setOwnerUserId(501L);
        batch.setTaskType(GroupBatchTaskType.REFRESH_LINK.code());
        batch.setStatus(GroupBatchTaskStatus.RUNNING.code());
        when(batchItemMapper.selectByCurrentCommandId(1L, "cmd-invite")).thenReturn(item);
        when(batchTaskMapper.selectByIdForExecution(901L)).thenReturn(batch);
        when(batchItemMapper.settleCurrentCommand(
                any(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()))).thenReturn(1);

        adapter().handleSnapshotResult(new ProtocolGroupSnapshotResultReportedEvent(
                "evt-invite", 1L, 100L, "acc-100", "WEB", 5001L,
                "120363000@g.us", "GROUP_BATCH_TASK_ITEM", 20L, 1,
                "cmd-invite", Map.of("INVITE_CODE", failed(
                        2_000L, "GROUP_INVITE_LINK_UNAVAILABLE")), "worker-1"));

        ArgumentCaptor<GroupBatchTaskItem> captor = ArgumentCaptor.forClass(GroupBatchTaskItem.class);
        verify(batchItemMapper).settleCurrentCommand(
                captor.capture(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()));
        assertThat(captor.getValue())
                .extracting(GroupBatchTaskItem::getStatus,
                        GroupBatchTaskItem::getErrorCode,
                        GroupBatchTaskItem::getDescription)
                .containsExactly(GroupBatchTaskItemStatus.FAILED.code(),
                        "GROUP_INVITE_LINK_UNAVAILABLE", "当前群没有可用邀请链接");
        verify(selector, never()).find(anyLong(), anyInt());
    }

    @Test
    void exhaustedRefreshLinkCandidatesUseBusinessFriendlyReason() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(21L);
        item.setTenantId(1L);
        item.setTaskId(902L);
        item.setGroupLinkId(5001L);
        item.setGroupJid("120363000@g.us");
        item.setAccountId(100L);
        item.setStatus(GroupBatchTaskItemStatus.WAITING_RESULT.code());
        item.setCurrentCommandId("cmd-bad-request");
        item.setAttemptCount(1);
        item.setCompletedScopeMask(0);
        item.setCandidateCursor(0);
        item.setUpdatedAt(1_000L);
        GroupBatchTask batch = new GroupBatchTask();
        batch.setId(902L);
        batch.setOwnerUserId(501L);
        batch.setTaskType(GroupBatchTaskType.REFRESH_LINK.code());
        batch.setStatus(GroupBatchTaskStatus.RUNNING.code());
        when(batchItemMapper.selectByCurrentCommandId(1L, "cmd-bad-request")).thenReturn(item);
        when(batchTaskMapper.selectByIdForExecution(902L)).thenReturn(batch);
        when(selector.find(5001L, 1)).thenReturn(Optional.empty());
        when(batchItemMapper.settleCurrentCommand(
                any(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()))).thenReturn(1);

        adapter().handleSnapshotResult(new ProtocolGroupSnapshotResultReportedEvent(
                "evt-bad-request", 1L, 100L, "acc-100", "WEB", 5001L,
                "120363000@g.us", "GROUP_BATCH_TASK_ITEM", 21L, 1,
                "cmd-bad-request", Map.of("INVITE_CODE", failed(2_000L, "UNKNOWN")), "worker-1"));

        ArgumentCaptor<GroupBatchTaskItem> captor = ArgumentCaptor.forClass(GroupBatchTaskItem.class);
        verify(batchItemMapper).settleCurrentCommand(
                captor.capture(), eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()));
        assertThat(captor.getValue())
                .extracting(GroupBatchTaskItem::getStatus,
                        GroupBatchTaskItem::getErrorCode,
                        GroupBatchTaskItem::getDescription)
                .containsExactly(GroupBatchTaskItemStatus.FAILED.code(),
                        "UNKNOWN", "获取群邀请链接失败，且没有其他可用账号可重试");
    }

    private GroupSnapshotResultReportedSinkAdapter adapter() {
        stubAccountBinding();
        return new GroupSnapshotResultReportedSinkAdapter(
                taskMapper, accountMapper, membershipStatusService,
                batchItemMapper, batchTaskMapper, selector,
                dispatchService, batchDispatchService,
                new GroupSnapshotProperties(true, 20, 1, 120_000L, 4, false),
                new GroupSnapshotMetrics());
    }

    private static GroupMetadataSyncTask task(int requestedMask, int completedMask, int cursor) {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setId(9001L);
        task.setTenantId(1L);
        task.setGroupLinkId(5001L);
        task.setOwnerUserId(501L);
        task.setGroupJid("120363000@g.us");
        task.setStatus(GroupMetadataSyncStatus.RUNNING.code());
        task.setExecutionAccountId(100L);
        task.setCurrentCommandId("cmd-1");
        task.setRequestedScopeMask(requestedMask);
        task.setCompletedScopeMask(completedMask);
        task.setCandidateCursor(cursor);
        task.setAttemptCount(cursor + 1);
        task.setLastStartedAt(1_000L);
        return task;
    }

    private static ProtocolGroupSnapshotResultReportedEvent event(
            Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        return event(1, scopes);
    }

    private static ProtocolGroupSnapshotResultReportedEvent event(
            int attemptNo,
            Map<String, ProtocolGroupSnapshotResultReportedEvent.ScopeResult> scopes) {
        return new ProtocolGroupSnapshotResultReportedEvent(
                "evt-1", 1L, 100L, "acc-100", "WEB", 5001L,
                "120363000@g.us", "GROUP_METADATA_SYNC", 9001L, attemptNo,
                "cmd-1", new LinkedHashMap<>(scopes), "worker-1");
    }

    private static ProtocolGroupSnapshotResultReportedEvent.ScopeResult success(long completedAt) {
        return new ProtocolGroupSnapshotResultReportedEvent.ScopeResult("SUCCESS", completedAt, null);
    }

    private static ProtocolGroupSnapshotResultReportedEvent.ScopeResult failed(
            long completedAt, String code) {
        return new ProtocolGroupSnapshotResultReportedEvent.ScopeResult("FAILED", completedAt, code);
    }
}
