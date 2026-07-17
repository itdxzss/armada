package com.armada.task.model.dto;

/**
 * 已耗尽传输重试的 outbox 命令与进群明细当前尝试的关联。
 *
 * <p>调度器跨租户扫描 DEAD 命令后，只把这组最小引用交给任务状态机；状态机仍会加锁并复核
 * commandId 和 attemptNo，防止旧命令的失败覆盖已经开始的新尝试。</p>
 *
 * @param tenantId 任务所属租户 ID
 * @param resultId 进群任务明细 ID
 * @param commandId 已进入 DEAD 的 outbox 命令 ID
 * @param attemptNo 该命令对应的业务尝试序号
 */
public record JoinTaskDeadCommandCandidate(
        Long tenantId,
        Long resultId,
        String commandId,
        int attemptNo
) {
}
