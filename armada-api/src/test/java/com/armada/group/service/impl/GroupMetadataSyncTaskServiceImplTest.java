package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupMetadataSyncLimits;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群详情同步任务状态机单测。 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataSyncTaskServiceImplTest {

    @Mock
    private GroupMetadataSyncTaskMapper mapper;

    @Test
    void enqueueDebouncesChangeEventsButRunsDurableFactsImmediately() {
        GroupMetadataSyncTaskServiceImpl service = service();

        service.enqueue(10L, GroupMetadataSyncTrigger.METADATA_CHANGED, 1_000L);
        service.enqueue(11L, GroupMetadataSyncTrigger.BASELINE_CAPTURED, 1_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper, org.mockito.Mockito.times(2)).enqueue(captor.capture(), anyInt());
        assertThat(captor.getAllValues().get(0).getNextRunAt()).isEqualTo(3_000L);
        assertThat(captor.getAllValues().get(1).getNextRunAt()).isEqualTo(1_000L);
        assertThat(captor.getAllValues())
                .extracting(GroupMetadataSyncTask::getCompletedScopeMask)
                .containsExactly(0, 0);
    }

    @Test
    void enqueueInviteCodeMarksMetadataAsAlreadyComplete() {
        GroupMetadataSyncTaskServiceImpl service = service();

        service.enqueueInviteCode(11L, GroupMetadataSyncTrigger.BASELINE_CAPTURED, 1_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor =
                ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).enqueue(captor.capture(), anyInt());
        assertThat(captor.getValue().getGroupLinkId()).isEqualTo(11L);
        assertThat(captor.getValue().getCompletedScopeMask()).isEqualTo(1);
    }

    @Test
    void failUsesOneFiveThirtyMinuteRetriesThenPermanentFailure() {
        GroupMetadataSyncTaskServiceImpl service = service();

        for (int attempt = 1; attempt <= 4; attempt++) {
            GroupMetadataSyncTask task = runningTask(attempt);
            service.fail(task, "NETWORK", "读取失败", 10_000L);
        }

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper, org.mockito.Mockito.times(4)).finish(captor.capture(), anyInt());
        assertThat(captor.getAllValues())
                .extracting(GroupMetadataSyncTask::getStatus)
                .containsExactly(
                        GroupMetadataSyncStatus.RETRY_WAIT.code(),
                        GroupMetadataSyncStatus.RETRY_WAIT.code(),
                        GroupMetadataSyncStatus.RETRY_WAIT.code(),
                        GroupMetadataSyncStatus.FAILED.code());
        assertThat(captor.getAllValues())
                .extracting(GroupMetadataSyncTask::getNextRunAt)
                .containsExactly(70_000L, 310_000L, 1_810_000L, null);
    }

    @Test
    void deferDoesNotConsumeAnAttemptAndOnlineResumesInviteOnlyTasks() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(0);
        task.setStatus(GroupMetadataSyncStatus.PENDING.code());

        service.defer(task, 5_000L);
        when(mapper.selectDeferredInviteTaskIdsForAccount(
                77L, GroupMetadataSyncStatus.DEFERRED.code(), 1)).thenReturn(List.of(11L, 12L));
        service.resumeDeferredInviteCodeForAccount(77L, 6_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).defer(captor.capture(), eq(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code())));
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMetadataSyncStatus.DEFERRED.code());
        assertThat(captor.getValue().getAttemptCount()).isZero();
        // 恢复只按已定位的主键写，不再用一条宽 UPDATE 扫锁全部延期行。
        verify(mapper).resumeDeferredByIds(
                List.of(11L, 12L),
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                6_000L);
    }

    @Test
    void onlineResumeSkipsTheWriteWhenAccountHasNoDeferredGroup() {
        GroupMetadataSyncTaskServiceImpl service = service();
        when(mapper.selectDeferredInviteTaskIdsForAccount(
                77L, GroupMetadataSyncStatus.DEFERRED.code(), 1)).thenReturn(List.of());

        service.resumeDeferredInviteCodeForAccount(77L, 6_000L);

        verify(mapper, never()).resumeDeferredByIds(any(), anyInt(), anyInt(), anyInt(), anyLong());
    }

    @Test
    void deferDoesNotRequeueSuccessfulSnapshotForPeriodicRefresh() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(0);
        task.setStatus(GroupMetadataSyncStatus.SUCCEEDED.code());
        task.setLastSuccessAt(4_000L);

        service.defer(task, 5_000L);

        verify(mapper, never()).defer(any(), any());
    }

    @Test
    void succeedClearsNextRunAtAndResetsFailureAttempts() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(3);

        service.succeed(task, 10_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).finish(captor.capture(), anyInt());
        GroupMetadataSyncTask completed = captor.getValue();
        assertThat(completed.getStatus()).isEqualTo(GroupMetadataSyncStatus.SUCCEEDED.code());
        assertThat(completed.getAttemptCount()).isZero();
        assertThat(completed.getNextRunAt()).isNull();
        assertThat(completed.getLastSuccessAt()).isEqualTo(10_000L);
    }

    @Test
    void findDueDoesNotSelectSucceededSnapshotEvenWhenItsHistoricalNextRunAtIsDue() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask triggered = runningTask(0);
        triggered.setId(1L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 20))
                .thenReturn(List.of(triggered));

        assertThat(service.findDue(10_000L, 20)).containsExactly(triggered);
        verify(mapper, never()).selectDueCandidates(
                eq(List.of(GroupMetadataSyncStatus.SUCCEEDED.code())),
                anyInt(),
                eq(10_000L),
                anyInt());
    }

    @Test
    void findDueLimitsAlreadySynchronizedPendingWorkToOneRefreshPerRun() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask firstRefresh = runningTask(0);
        firstRefresh.setId(1L);
        firstRefresh.setStatus(GroupMetadataSyncStatus.PENDING.code());
        firstRefresh.setLastSuccessAt(8_000L);
        GroupMetadataSyncTask secondRefresh = runningTask(0);
        secondRefresh.setId(2L);
        secondRefresh.setStatus(GroupMetadataSyncStatus.RETRY_WAIT.code());
        secondRefresh.setLastSuccessAt(7_000L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 20))
                .thenReturn(List.of(firstRefresh, secondRefresh));

        assertThat(service.findDue(10_000L, 20)).containsExactly(firstRefresh);
    }

    @Test
    void findDuePreservesRealtimeEventAndManualRefreshThroughput() {
        GroupMetadataSyncTask participantChanged = runningTask(0);
        participantChanged.setId(1L);
        participantChanged.setStatus(GroupMetadataSyncStatus.PENDING.code());
        participantChanged.setTriggerSource(GroupMetadataSyncTrigger.PARTICIPANT_CHANGED.code());
        participantChanged.setLastSuccessAt(8_000L);
        GroupMetadataSyncTask manualRefresh = runningTask(0);
        manualRefresh.setId(2L);
        manualRefresh.setStatus(GroupMetadataSyncStatus.PENDING.code());
        manualRefresh.setTriggerSource(GroupMetadataSyncTrigger.MANUAL_REFRESH.code());
        manualRefresh.setLastSuccessAt(7_000L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 20))
                .thenReturn(List.of(participantChanged, manualRefresh));

        assertThat(service().findDue(10_000L, 20))
                .containsExactly(participantChanged, manualRefresh);
    }

    @Test
    void findDueCompletesMetadataScopeForFreshClassificationProfile() {
        GroupMetadataSyncTask baseline = runningTask(0);
        baseline.setStatus(GroupMetadataSyncStatus.PENDING.code());
        baseline.setTriggerSource(GroupMetadataSyncTrigger.BASELINE_CAPTURED.code());
        baseline.setUpdatedAt(10_000L);
        baseline.setMemberSnapshotAt(9_500L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 12_000L, 20))
                .thenReturn(List.of(baseline));

        assertThat(service().findDue(12_000L, 20)).containsExactly(baseline);
        assertThat(baseline.getCompletedScopeMask()).isEqualTo(1);
    }

    @Test
    void findDueDoesNotReuseStaleProfileOrBypassManualMetadataRefresh() {
        GroupMetadataSyncTask staleClassification = runningTask(0);
        staleClassification.setId(1L);
        staleClassification.setStatus(GroupMetadataSyncStatus.PENDING.code());
        staleClassification.setTriggerSource(GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED.code());
        staleClassification.setUpdatedAt(300_000L);
        staleClassification.setMemberSnapshotAt(100_000L);
        GroupMetadataSyncTask manualRefresh = runningTask(0);
        manualRefresh.setId(2L);
        manualRefresh.setStatus(GroupMetadataSyncStatus.PENDING.code());
        manualRefresh.setTriggerSource(GroupMetadataSyncTrigger.MANUAL_REFRESH.code());
        manualRefresh.setUpdatedAt(300_000L);
        manualRefresh.setMemberSnapshotAt(299_500L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 301_000L, 20))
                .thenReturn(List.of(staleClassification, manualRefresh));

        assertThat(service().findDue(301_000L, 20))
                .containsExactly(staleClassification, manualRefresh);
        assertThat(staleClassification.getCompletedScopeMask()).isNull();
        assertThat(manualRefresh.getCompletedScopeMask()).isNull();
    }

    @Test
    void claimOnlyAllowsTriggeredStatuses() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask pending = runningTask(0);
        pending.setStatus(GroupMetadataSyncStatus.PENDING.code());
        GroupExecutionAccount account = new GroupExecutionAccount(
                77L, "web", "protocol-account", "919000000000", true);
        when(mapper.claim(
                any(GroupMetadataSyncTask.class),
                eq(List.of(
                        GroupMetadataSyncStatus.PENDING.code(),
                        GroupMetadataSyncStatus.RETRY_WAIT.code())),
                eq(GroupMetadataSyncStatus.RUNNING.code()),
                eq(GroupMetadataSyncStatus.SUCCEEDED.code()),
                eq(3),
                eq(1))).thenReturn(1);

        assertThat(service.claim(
                pending, account, 10_000L, 20_000L, new GroupMetadataSyncLimits(3, 1)))
                .isTrue();
        assertThat(pending.getStatus()).isEqualTo(GroupMetadataSyncStatus.RUNNING.code());
        assertThat(pending.getAttemptCount()).isEqualTo(1);
    }

    private GroupMetadataSyncTaskServiceImpl service() {
        return new GroupMetadataSyncTaskServiceImpl(
                mapper, 2_000L, 120_000L);
    }

    private static GroupMetadataSyncTask runningTask(int attempts) {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setGroupLinkId(10L);
        task.setStatus(GroupMetadataSyncStatus.RUNNING.code());
        task.setAttemptCount(attempts);
        task.setRerunRequested(false);
        return task;
    }
}
