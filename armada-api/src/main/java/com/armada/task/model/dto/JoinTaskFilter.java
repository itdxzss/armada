package com.armada.task.model.dto;

/**
 * 进群任务列表筛选条件，供 {@code JoinTaskMapper.countPage} / {@code selectPage} 复用。
 *
 * <p>字段均可为 null：null 表示该维度不筛。
 * {@code keyword} 同时命中任务名称和链接文本（LIKE 模糊）。
 * {@code dateFrom}/{@code dateTo} 为 epoch 毫秒，左闭右开（created_at >= dateFrom AND created_at < dateTo）。</p>
 *
 * @param keyword          关键字（命中 name 或 links_text）
 * @param status           状态枚举码（DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED）
 * @param groupId          账号分组 ID（用 LIKE 匹配 account_group_ids JSON 快照）
 * @param distributionMode 分配方式枚举码（FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK）
 * @param interval         进群间隔展示标签（匹配 interval_label）
 * @param dateFrom         创建时间下限（epoch 毫秒，含）
 * @param dateTo           创建时间上限（epoch 毫秒，不含）
 */
public record JoinTaskFilter(
        String keyword,
        String status,
        Long groupId,
        String distributionMode,
        String interval,
        Long dateFrom,
        Long dateTo
) {
}
