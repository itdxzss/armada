package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 批量获取最新群信息执行器单测：只观察耐久队列，不自己拉协议。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupBatchInfoRefreshWorkerTest {

    private static final long GROUP_LINK_ID = 101L;
    private static final long BASELINE = 5_000L;

    @Mock
    private GroupMetadataSyncTaskMapper syncTaskMapper;

    @Mock
    private GroupBatchTaskSettlement settlement;

    @Test
    void itemSucceedsOnceSyncSucceededAfterTheSubmittedBaseline() {
        when(syncTaskMapper.selectByGroupLinkId(GROUP_LINK_ID))
                .thenReturn(sync(GroupMetadataSyncStatus.SUCCEEDED, 6_000L));

        worker().execute(item(), 9_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.SUCCESS.code());
        assertThat(outcome.getOperatedAt()).isEqualTo(9_000L);
    }

    @Test
    void staleSuccessBeforeTheBaselineKeepsTheItemPending() {
        // 提交之前的旧快照不算数，否则整批会瞬间"成功"而根本没刷新过。
        when(syncTaskMapper.selectByGroupLinkId(GROUP_LINK_ID))
                .thenReturn(sync(GroupMetadataSyncStatus.SUCCEEDED, 4_000L));

        worker().execute(item(), 9_000L);

        verify(settlement, never()).settle(any());
    }

    @Test
    void itemFailsOnlyWhenTheDurableQueueReachedItsTerminalFailure() {
        GroupMetadataSyncTask failed = sync(GroupMetadataSyncStatus.FAILED, null);
        failed.setLastErrorCode("PROTOCOL_TIMEOUT");
        failed.setLastErrorMessage("群详情同步执行失败");
        when(syncTaskMapper.selectByGroupLinkId(GROUP_LINK_ID)).thenReturn(failed);

        worker().execute(item(), 9_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(outcome.getErrorCode()).isEqualTo("PROTOCOL_TIMEOUT");
        assertThat(outcome.getDescription()).isNotBlank();
    }

    @Test
    void retryWaitIsNotTerminalSoTheItemKeepsWaiting() {
        when(syncTaskMapper.selectByGroupLinkId(GROUP_LINK_ID))
                .thenReturn(sync(GroupMetadataSyncStatus.RETRY_WAIT, null));

        worker().execute(item(), 9_000L);

        verify(settlement, never()).settle(any());
    }

    private GroupBatchTaskItem settled() {
        ArgumentCaptor<GroupBatchTaskItem> captor =
                ArgumentCaptor.forClass(GroupBatchTaskItem.class);
        verify(settlement).settle(captor.capture());
        return captor.getValue();
    }

    private static GroupMetadataSyncTask sync(GroupMetadataSyncStatus status, Long lastSuccessAt) {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setGroupLinkId(GROUP_LINK_ID);
        task.setStatus(status.code());
        task.setLastSuccessAt(lastSuccessAt);
        return task;
    }

    private static GroupBatchTaskItem item() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);
        item.setTaskId(900L);
        item.setGroupLinkId(GROUP_LINK_ID);
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        item.setBaselineSyncedAt(BASELINE);
        return item;
    }

    private GroupBatchInfoRefreshWorker worker() {
        return new GroupBatchInfoRefreshWorker(syncTaskMapper, settlement);
    }
}
