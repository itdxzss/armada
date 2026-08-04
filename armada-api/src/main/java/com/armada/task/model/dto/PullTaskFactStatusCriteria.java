package com.armada.task.model.dto;

import java.util.List;

/** 按所属事实 ID 和状态集合查询未收敛行数。 */
public record PullTaskFactStatusCriteria(long ownerId, List<Integer> statuses) {

    /** 固化状态集合，空集合没有合法 SQL 语义。 */
    public PullTaskFactStatusCriteria {
        statuses = List.copyOf(statuses);
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("状态条件不能为空");
        }
    }
}
