package com.armada.task.model.dto;

import java.util.List;

/** 批量软删除的任务范围、类型状态规则和时间。 */
public record PullTaskDeleteCriteria(
        List<Long> taskIds,
        List<PullTaskDeleteRule> rules,
        long deletedAt) {

    /** 固化集合并拒绝生成空 IN 或空规则。 */
    public PullTaskDeleteCriteria {
        taskIds = List.copyOf(taskIds);
        rules = List.copyOf(rules);
        if (taskIds.isEmpty() || rules.isEmpty()) {
            throw new IllegalArgumentException("删除任务与状态规则不能为空");
        }
    }
}
