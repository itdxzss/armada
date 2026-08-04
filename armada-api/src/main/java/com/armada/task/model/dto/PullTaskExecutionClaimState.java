package com.armada.task.model.dto;

import java.util.List;

/**
 * 调度器允许抢占的一组执行状态与业务阶段。
 *
 * @param executionStatus 执行行状态码
 * @param stages          该状态下允许调度的阶段码，非空
 */
public record PullTaskExecutionClaimState(int executionStatus, List<Integer> stages) {

    /** 防止调用方后续修改阶段集合，导致同一轮 SQL 条件漂移。 */
    public PullTaskExecutionClaimState {
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("调度阶段不能为空");
        }
    }
}
