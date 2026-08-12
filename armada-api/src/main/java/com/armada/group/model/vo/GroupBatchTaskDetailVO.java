package com.armada.group.model.vo;

import java.util.List;

/**
 * 批量任务进度与结果明细。
 *
 * @param taskId 批量任务 ID
 * @param taskType 批量操作类型名
 * @param status 任务主状态名
 * @param terminal 是否终态;前端据此停止轮询
 * @param createdAt 任务创建时间(epoch 毫秒)
 * @param completedAt 进入终态时间(epoch 毫秒)
 * @param totalCount 有效处理项数
 * @param successCount 成功项数
 * @param failedCount 失败项数
 * @param items 已终结的逐项结果;运行中即实时追加
 */
public record GroupBatchTaskDetailVO(
        Long taskId,
        String taskType,
        String status,
        boolean terminal,
        Long createdAt,
        Long completedAt,
        int totalCount,
        int successCount,
        int failedCount,
        List<GroupBatchTaskItemVO> items) {
}
