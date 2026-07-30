package com.armada.group.model.dto;

/**
 * 创建历史群单群拉人执行的 multipart 元数据。
 *
 * @param sourceAccountGroupId 来源历史群账号组 ID
 * @param groupJid             账号组历史 baseline 内的目标群 JID
 * @param pullerAccountGroupId 拉手账号分组 ID
 * @param singleAddCount       单次 participants/add 最大合计人数
 * @param idempotencyKey       当前租户内创建幂等键
 */
public record HistoricalGroupPullCreateDTO(
        Long sourceAccountGroupId,
        String groupJid,
        Long pullerAccountGroupId,
        Integer singleAddCount,
        String idempotencyKey) {
}
