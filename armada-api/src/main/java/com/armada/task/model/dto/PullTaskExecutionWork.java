package com.armada.task.model.dto;

/**
 * 已取得父任务并发槽位、可在事务外执行后续阶段的工作项。
 *
 * @param tenantId      所属租户 ID
 * @param executionId   执行行 ID
 * @param normalizedLink 归一化群链接
 * @param inviteCode    群邀请码
 * @param lease         调度租约与乐观锁快照
 */
public record PullTaskExecutionWork(Long tenantId, Long executionId,
                                    String normalizedLink, String inviteCode,
                                    PullTaskExecutionLease lease) {

    /** @return 本次结果回写期望的版本号 */
    public int expectedVersion() {
        return lease.expectedVersion();
    }

    /** @return 当前租约持有者 */
    public String lockOwner() {
        return lease.lockOwner();
    }
}
