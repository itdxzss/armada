package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final long PERIODIC_REFRESH_MS = 60_000L;

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
    void deferDoesNotConsumeAnAttemptAndOnlineResumesMatchingGroups() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(0);
        task.setStatus(GroupMetadataSyncStatus.PENDING.code());

        service.defer(task, 5_000L);
        service.resumeDeferredForAccount(77L, 6_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).defer(captor.capture(), eq(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code())));
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMetadataSyncStatus.DEFERRED.code());
        assertThat(captor.getValue().getAttemptCount()).isZero();
        verify(mapper).resumeDeferredForAccount(
                77L,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                6_000L);
    }

    @Test
    void deferPeriodicRefreshKeepsSuccessfulSnapshotAvailableAndReschedulesIt() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(0);
        task.setStatus(GroupMetadataSyncStatus.SUCCEEDED.code());
        task.setLastSuccessAt(4_000L);

        service.defer(task, 5_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).defer(captor.capture(), eq(List.of(
                GroupMetadataSyncStatus.SUCCEEDED.code())));
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMetadataSyncStatus.SUCCEEDED.code());
        assertThat(captor.getValue().getNextRunAt()).isEqualTo(65_000L);
        assertThat(captor.getValue().getLastErrorCode()).isNull();
        assertThat(captor.getValue().getLastErrorMessage()).isNull();
    }

    @Test
    void succeedSchedulesPeriodicRefreshAndResetsFailureAttempts() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask task = runningTask(3);

        service.succeed(task, 10_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).finish(captor.capture(), anyInt());
        GroupMetadataSyncTask completed = captor.getValue();
        assertThat(completed.getStatus()).isEqualTo(GroupMetadataSyncStatus.SUCCEEDED.code());
        assertThat(completed.getAttemptCount()).isZero();
        assertThat(completed.getNextRunAt()).isEqualTo(70_000L);
        assertThat(completed.getLastSuccessAt()).isEqualTo(10_000L);
    }

    @Test
    void findDueLimitsPeriodicRefreshesSoTheyCannotDelayNewGroupSynchronization() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask triggered = runningTask(0);
        triggered.setId(1L);
        GroupMetadataSyncTask periodic = runningTask(0);
        periodic.setId(2L);
        when(mapper.selectDueCandidates(List.of(
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncStatus.RETRY_WAIT.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 20))
                .thenReturn(List.of(triggered));
        when(mapper.selectDueCandidates(
                List.of(GroupMetadataSyncStatus.SUCCEEDED.code()),
                GroupMetadataSyncStatus.SUCCEEDED.code(), 10_000L, 1))
                .thenReturn(List.of(periodic));

        assertThat(service.findDue(10_000L, 20)).containsExactly(triggered, periodic);
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
    void claimAcceptsSucceededTaskSelectedForPeriodicRefresh() {
        GroupMetadataSyncTaskServiceImpl service = service();
        GroupMetadataSyncTask periodic = runningTask(0);
        periodic.setStatus(GroupMetadataSyncStatus.SUCCEEDED.code());
        periodic.setNextRunAt(null);
        GroupExecutionAccount account = new GroupExecutionAccount(
                77L, "web", "protocol-account", "919000000000", true);
        when(mapper.claim(
                any(GroupMetadataSyncTask.class),
                eq(List.of(
                        GroupMetadataSyncStatus.PENDING.code(),
                        GroupMetadataSyncStatus.RETRY_WAIT.code(),
                        GroupMetadataSyncStatus.SUCCEEDED.code())),
                eq(GroupMetadataSyncStatus.RUNNING.code()),
                eq(GroupMetadataSyncStatus.SUCCEEDED.code()),
                eq(3),
                eq(1))).thenReturn(1);

        assertThat(service.claim(
                periodic, account, 10_000L, 20_000L, new GroupMetadataSyncLimits(3, 1)))
                .isTrue();
        assertThat(periodic.getStatus()).isEqualTo(GroupMetadataSyncStatus.RUNNING.code());
        assertThat(periodic.getAttemptCount()).isEqualTo(1);
    }

    private GroupMetadataSyncTaskServiceImpl service() {
        return new GroupMetadataSyncTaskServiceImpl(
                mapper, 2_000L, PERIODIC_REFRESH_MS);
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
