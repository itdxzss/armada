package com.armada.task.model.dto;

/** RD-02 单群执行工作台服务端筛选条件。 */
public record PullTaskStandardExecutionFilter(
        long taskId,
        String keyword,
        Integer executionStatus,
        Integer stage,
        Integer waitResourceType,
        Integer manualPaused) {
}
