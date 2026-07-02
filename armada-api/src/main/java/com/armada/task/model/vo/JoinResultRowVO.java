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
 */
public record JoinResultRowVO(
        String account,
        String link,
        String status,
        String reason,
        String reasonLabel,
        boolean isAdmin) {
}
