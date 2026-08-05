package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
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
        GroupMetadataSyncTaskServiceImpl service = new GroupMetadataSyncTaskServiceImpl(mapper, 2_000L);

        service.enqueue(10L, GroupMetadataSyncTrigger.METADATA_CHANGED, 1_000L);
        service.enqueue(11L, GroupMetadataSyncTrigger.BASELINE_CAPTURED, 1_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper, org.mockito.Mockito.times(2)).enqueue(captor.capture(), anyInt());
        assertThat(captor.getAllValues().get(0).getNextRunAt()).isEqualTo(3_000L);
        assertThat(captor.getAllValues().get(1).getNextRunAt()).isEqualTo(1_000L);
    }

    @Test
    void failUsesOneFiveThirtyMinuteRetriesThenPermanentFailure() {
        GroupMetadataSyncTaskServiceImpl service = new GroupMetadataSyncTaskServiceImpl(mapper, 2_000L);

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
        GroupMetadataSyncTaskServiceImpl service = new GroupMetadataSyncTaskServiceImpl(mapper, 2_000L);
        GroupMetadataSyncTask task = runningTask(0);

        service.defer(task, 5_000L);
        service.resumeDeferredForAccount(77L, 6_000L);

        ArgumentCaptor<GroupMetadataSyncTask> captor = ArgumentCaptor.forClass(GroupMetadataSyncTask.class);
        verify(mapper).defer(captor.capture(), org.mockito.ArgumentMatchers.anyList());
        assertThat(captor.getValue().getStatus()).isEqualTo(GroupMetadataSyncStatus.DEFERRED.code());
        assertThat(captor.getValue().getAttemptCount()).isZero();
        verify(mapper).resumeDeferredForAccount(
                77L,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncStatus.PENDING.code(),
                GroupMetadataSyncTrigger.ACCOUNT_ONLINE.code(),
                6_000L);
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
