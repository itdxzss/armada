package com.armada.group.service.impl;

import static org.mockito.Mockito.verify;

import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.service.GroupBatchSnapshotDispatchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 批量群信息 Worker 只负责把明细交给 Kafka 快照派发器。 */
@ExtendWith(MockitoExtension.class)
class GroupBatchInfoRefreshWorkerTest {

    @Mock
    private GroupBatchSnapshotDispatchService dispatchService;

    @Test
    void dispatchesMetadataSnapshotWithoutCallingLegacyHttpPath() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);

        new GroupBatchInfoRefreshWorker(dispatchService).execute(item, 7_000L);

        verify(dispatchService).dispatch(item, GroupBatchTaskType.REFRESH_INFO, 7_000L);
    }
}
