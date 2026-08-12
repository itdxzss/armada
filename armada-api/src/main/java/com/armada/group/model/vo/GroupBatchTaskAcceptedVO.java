package com.armada.group.model.vo;

/**
 * 批量任务受理结果。
 *
 * @param taskId 批量任务 ID
 * @param createdAt 任务创建时间(epoch 毫秒)
 * @param status 任务主状态名
 */
public record GroupBatchTaskAcceptedVO(Long taskId, Long createdAt, String status) {
}
