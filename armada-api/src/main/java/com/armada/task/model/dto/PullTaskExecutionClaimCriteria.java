package com.armada.task.model.dto;

import java.util.List;

/**
 * 跨租户执行行抢占条件；业务状态由调度代码传入，Mapper XML 不固化状态机。
 *
 * @param lease           批量范围与租约条件
 * @param eligibleStates  允许抢占的执行状态与阶段组合，非空
 * @param parent          父任务类型、模式与状态条件
 * @param eligibleManualPaused 允许抢占的人工暂停标记
 */
public record PullTaskExecutionClaimCriteria(
        Lease lease,
        List<PullTaskExecutionClaimState> eligibleStates,
        Parent parent,
        int eligibleManualPaused) {

    /** 兼容测试和非调度调用；默认值仍在 Java 领域层明确给出。 */
    public PullTaskExecutionClaimCriteria(
            Lease lease,
            List<PullTaskExecutionClaimState> eligibleStates,
            Parent parent) {
        this(lease, eligibleStates, parent, 0);
    }

    /** 固化本轮条件快照，避免执行 SQL 前集合被外部修改。 */
    public PullTaskExecutionClaimCriteria {
        eligibleStates = List.copyOf(eligibleStates);
        if (eligibleStates.isEmpty()) {
            throw new IllegalArgumentException("调度状态条件不能为空");
        }
    }

    /**
     * @param limit         单批最大抢占数
     * @param now           当前时间(epoch 毫秒)
     * @param lockOwner     当前实例租约标识
     * @param lockExpiresAt 租约过期时间(epoch 毫秒)
     */
    public record Lease(int limit, long now, String lockOwner, long lockExpiresAt) {
    }

    /**
     * @param taskType   父任务类型
     * @param taskMode   父任务模式
     * @param taskStatus 父任务状态
     */
    public record Parent(String taskType, String taskMode, String taskStatus) {
    }
}
