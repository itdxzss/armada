package com.armada.task.model.dto;

/**
 * 跨租户到期扫描返回的最小派发引用。
 *
 * <p>首次扫描故意不加载业务详情也不加行锁；协调器按租户分组后，再进入带租户上下文的短事务复核。</p>
 *
 * @param tenantId 任务所属租户 ID
 * @param resultId 已到期的进群任务明细 ID
 */
public record JoinTaskDispatchCandidate(Long tenantId, Long resultId) {
}
