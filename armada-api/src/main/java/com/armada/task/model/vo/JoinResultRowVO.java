package com.armada.task.model.vo;

/**
 * 进群任务明细行出参 VO(每账号每链接一行,camelCase wire)。
 *
 * <p>link 直出原始群链接(系统不脱敏);status/reason 直出库值,reasonLabel 为页面展示文案,
 * isAdmin 由 Kafka 回写。</p>
 *
 * @param account 执行账号号码/别名(快照)
 * @param link    群链接(原样,不脱敏)
 * @param status  进群结果码:PENDING/SUCCESS/FAILED
 * @param reason      失败原因码或摘要(原始库值)
 * @param reasonLabel 失败原因中文展示;成功或待执行时为空
 * @param isAdmin     是否已成管理员
 * @param approvalStatus 待审核自动处理阶段，未触发时为空
 * @param approvalReason 处理进度或明确失败说明
 * @param approvalActorAccountId 关闭审核的原管理员，独立于提权执行者
 */
public record JoinResultRowVO(
        String account,
        String link,
        String status,
        String reason,
        String reasonLabel,
        boolean isAdmin,
        Long id,
        String adminStatus,
        String adminReason,
        Long adminActorAccountId,
        String stepStatus,
        Long joinedAt,
        Long promotedAt,
        String cleanupStatus,
        String cleanupReason,
        int cleanupCompleted,
        int cleanupTotal,
        String approvalStatus,
        String approvalReason,
        Long approvalActorAccountId) {
}
