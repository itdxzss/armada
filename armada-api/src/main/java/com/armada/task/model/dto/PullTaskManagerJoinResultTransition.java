package com.armada.task.model.dto;

/**
 * 协议回调无调度租约时推进管理员入群执行检查点的 CAS 条件。
 *
 * @param executionId 执行行 ID
 * @param taskId 父任务 ID
 * @param expectedVersion 读取到的乐观锁版本
 * @param expected 允许的当前状态和阶段
 * @param target 目标检查点事实
 * @param now 回写时间
 */
public record PullTaskManagerJoinResultTransition(
        long executionId,
        long taskId,
        int expectedVersion,
        Expected expected,
        Target target,
        long now
) {
    /**
     * @param executionStatus 期望执行状态
     * @param stage 期望业务阶段
     */
    public record Expected(int executionStatus, int stage) {
    }

    /**
     * @param executionStatus 目标执行状态
     * @param stage 目标业务阶段
     * @param groupJid 协议确认的群 JID
     * @param waitResourceType 等待资源类型
     * @param reasonCode 稳定原因码
     * @param reasonMessage 展示原因
     * @param nextRunAt 下次调度时间
     * @param finishedAt 终态完成时间
     */
    public record Target(
            int executionStatus,
            int stage,
            String groupJid,
            Integer waitResourceType,
            String reasonCode,
            String reasonMessage,
            long nextRunAt,
            Long finishedAt
    ) {
    }
}
