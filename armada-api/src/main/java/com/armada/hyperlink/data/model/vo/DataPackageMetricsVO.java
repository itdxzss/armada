package com.armada.hyperlink.data.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 数据包列表的当前代池状态指标。
 *
 * @param totalCount 当前代总数
 * @param unusedCount 未使用数
 * @param usedCount 已使用数
 * @param sentCount 当前单钩数
 * @param deliveredCount 已送达数
 * @param failedCount 可重试失败与未注册之和
 * @param unregisteredCount 未注册数
 * @param clickUvCount 尚未接入超链任务点击事实时固定为零
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataPackageMetricsVO(
        int totalCount,
        int unusedCount,
        int usedCount,
        int sentCount,
        int deliveredCount,
        int failedCount,
        int unregisteredCount,
        int clickUvCount) {
}
