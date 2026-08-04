package com.armada.task.model.dto;

/**
 * 父任务执行槽位的条件抢占参数。
 *
 * @param candidate 候选执行行状态、阶段、版本与租约条件
 * @param parent    父任务状态与版本条件
 * @param policy    当前槽位占用口径与并发上限
 * @param now       更新时间(epoch 毫秒)
 */
public record PullTaskExecutionSlotClaim(
        Candidate candidate,
        Parent parent,
        Policy policy,
        long now) {

    /**
     * @param taskId          父任务 ID
     * @param executionId     候选执行行 ID
     * @param executionStatus 候选行尚未占用槽位时的状态码
     * @param executionStage  候选行预期阶段码
     * @param lease           候选行租约持有者与预期版本
     */
    public record Candidate(
            long taskId,
            long executionId,
            int executionStatus,
            int executionStage,
            PullTaskExecutionLease lease) {
    }

    /**
     * @param expectedVersion 读取父任务时的版本号
     * @param taskType        允许的父任务类型
     * @param taskMode        允许的父任务模式
     * @param taskStatus      允许的父任务状态
     */
    public record Parent(
            int expectedVersion,
            String taskType,
            String taskMode,
            String taskStatus) {
    }

    /**
     * @param runningExecutionStatus 占用父任务槽位的执行行状态码
     * @param concurrentLimit        最大并发执行行数
     */
    public record Policy(int runningExecutionStatus, int concurrentLimit) {
    }
}
