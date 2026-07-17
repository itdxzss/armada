package com.armada.group.model.dto;

/**
 * 创建历史群单群拉人执行的 multipart 元数据。
 *
 * @param operationAccountId   固定操作账号 ID
 * @param groupJid             操作账号 baseline 内的目标群 JID
 * @param pullerAccountGroupId 拉手账号分组 ID
 * @param singleAddCount       单次 participants/add 最大合计人数
 * @param idempotencyKey       当前租户内创建幂等键
 */
public record HistoricalGroupPullCreateDTO(
        Long operationAccountId,
        String groupJid,
        Long pullerAccountGroupId,
        Integer singleAddCount,
        String idempotencyKey) {
}
