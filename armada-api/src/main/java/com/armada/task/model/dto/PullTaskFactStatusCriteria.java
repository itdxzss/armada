package com.armada.task.model.dto;

import java.util.List;

/** 按执行行、拉人调用和状态集合判断是否还有未收敛事实。 */
public record PullTaskFactStatusCriteria(long groupExecutionId, long pullCallId, List<Integer> statuses) {

    /** 固化状态集合，空集合没有合法 SQL 语义。 */
    public PullTaskFactStatusCriteria {
        statuses = List.copyOf(statuses);
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("状态条件不能为空");
        }
    }
}
