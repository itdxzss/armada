package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import org.springframework.stereotype.Component;

/**
 * 批量获取最新群信息的单项执行器。
 *
 * <p>本执行器不自己调协议：提交阶段已把每个群排进 group_metadata_sync_task 的批量档，
 * 真正的拉取、字段级空值保护和退避重试都由既有耐久队列完成。这里只负责观察结果并结算明细，
 * 避免出现第二套同步逻辑。</p>
 */
@Component
public class GroupBatchInfoRefreshWorker {

    private static final String SUCCESS_MESSAGE = "群信息已刷新";
    private static final String FAILURE_FALLBACK_CODE = "METADATA_SYNC_FAILED";
    private static final String FAILURE_FALLBACK_MESSAGE = "群详情同步失败";

    private final GroupMetadataSyncTaskMapper syncTaskMapper;
    private final GroupBatchTaskSettlement settlement;

    /** 创建批量获取最新群信息执行器。 */
    public GroupBatchInfoRefreshWorker(
            GroupMetadataSyncTaskMapper syncTaskMapper, GroupBatchTaskSettlement settlement) {
        this.syncTaskMapper = syncTaskMapper;
        this.settlement = settlement;
    }

    /**
     * 观察耐久队列结果并结算一条明细。
     *
     * <p>只有提交基线之后发生的同步成功才算数；仍在重试或尚未排上的项保持待执行，下一轮再看。</p>
     *
     * @param item 待执行明细
     * @param now 结算时间(epoch 毫秒)
     */
    public void execute(GroupBatchTaskItem item, long now) {
        GroupMetadataSyncTask sync = syncTaskMapper.selectByGroupLinkId(item.getGroupLinkId());
        if (sync == null) {
            return;
        }
        if (refreshedAfterBaseline(item, sync)) {
            settlement.settle(succeeded(item, now));
            return;
        }
        // RETRY_WAIT 不是终态，队列还会自己重试；只有耗尽退避进入 FAILED 才判该项失败。
        if (GroupMetadataSyncStatus.FAILED.code() == valueOrZero(sync.getStatus())) {
            settlement.settle(failed(item, sync, now));
        }
    }

    private static boolean refreshedAfterBaseline(
            GroupBatchTaskItem item, GroupMetadataSyncTask sync) {
        Long lastSuccessAt = sync.getLastSuccessAt();
        Long baseline = item.getBaselineSyncedAt();
        return lastSuccessAt != null && baseline != null && lastSuccessAt > baseline;
    }

    private static GroupBatchTaskItem succeeded(GroupBatchTaskItem item, long now) {
        GroupBatchTaskItem outcome = outcome(item, now);
        outcome.setStatus(GroupBatchTaskItemStatus.SUCCESS.code());
        outcome.setDescription(SUCCESS_MESSAGE);
        return outcome;
    }

    private static GroupBatchTaskItem failed(
            GroupBatchTaskItem item, GroupMetadataSyncTask sync, long now) {
        GroupBatchTaskItem outcome = outcome(item, now);
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(blankToDefault(sync.getLastErrorCode(), FAILURE_FALLBACK_CODE));
        outcome.setDescription(
                blankToDefault(sync.getLastErrorMessage(), FAILURE_FALLBACK_MESSAGE));
        return outcome;
    }

    private static GroupBatchTaskItem outcome(GroupBatchTaskItem item, long now) {
        GroupBatchTaskItem outcome = new GroupBatchTaskItem();
        outcome.setId(item.getId());
        outcome.setTaskId(item.getTaskId());
        outcome.setGroupLinkId(item.getGroupLinkId());
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        return outcome;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
