package com.armada.group.service.impl;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 批量任务逐项结算：终结明细并原子递增任务汇总。 */
@Component
public class GroupBatchTaskSettlement {

    private final GroupBatchTaskItemMapper itemMapper;
    private final GroupBatchTaskMapper taskMapper;

    /** 创建逐项结算器。 */
    public GroupBatchTaskSettlement(
            GroupBatchTaskItemMapper itemMapper, GroupBatchTaskMapper taskMapper) {
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 在独立事务内结算一条明细。
     *
     * <p>一项一事务是进度能被前端轮询看到的前提；整批一个事务会让进度从 0% 直接跳到 100%。
     * 协议调用必须在本方法之外完成，不得把网络 I/O 圈进事务。</p>
     *
     * <p>明细已被他人终结时 finishItem 返回 0 行，此时跳过汇总递增，
     * 避免执行器重入或多实例竞争把同一项计入两次、进度超过总数。</p>
     *
     * @param outcome 已填好终态、执行账号、原因与结束时间的明细
     */
    @Transactional(rollbackFor = Exception.class)
    public void settle(GroupBatchTaskItem outcome) {
        int finished = itemMapper.finishItem(outcome, GroupBatchTaskItemStatus.PENDING.code());
        if (finished == 0) {
            return;
        }
        taskMapper.applyItemOutcome(
                outcome.getTaskId(),
                GroupBatchTaskItemStatus.SUCCESS.code() == outcome.getStatus(),
                GroupBatchTaskStatus.COMPLETED.code(),
                GroupBatchTaskStatus.RUNNING.code(),
                outcome.getOperatedAt());
    }
}
