package com.armada.group.service.impl;

import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.service.GroupBatchSnapshotDispatchService;
import org.springframework.stereotype.Component;

/** 批量刷新群链接执行器：只派发邀请码快照命令，不同步等待协议 HTTP。 */
@Component
public class GroupBatchLinkRefreshWorker {

    private final GroupBatchSnapshotDispatchService dispatchService;

    /** 创建批量邀请码命令派发器。 */
    public GroupBatchLinkRefreshWorker(GroupBatchSnapshotDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 写入 Outbox 并把明细推进到等待结果。 */
    public void execute(GroupBatchTaskItem item, long now) {
        dispatchService.dispatch(item, GroupBatchTaskType.REFRESH_LINK, now);
    }
}
