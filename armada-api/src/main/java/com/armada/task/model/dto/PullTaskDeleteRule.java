package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskType;
import java.util.List;

/** 一种任务类型允许软删除的当前状态集合。 */
public record PullTaskDeleteRule(PullTaskType taskType, List<String> statuses) {

    /** 固化状态集合并拒绝生成空 IN。 */
    public PullTaskDeleteRule {
        statuses = List.copyOf(statuses);
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("允许删除状态不能为空");
        }
    }
}
