package com.armada.task.model.dto;

/**
 * 协议层统一进群结果在任务域内的事件模型。
 *
 * <p>任务状态机只接受仍处于 SUBMITTED 且命令 ID、尝试序号完全匹配的明细，借此实现重复消费和迟到
 * 事件幂等。协议诊断字段保留在模型中，但不参与账号 lane 的推进判定。</p>
 *
 * @param eventId 协议事件 ID，用于日志关联
 * @param tenantId 任务所属租户 ID
 * @param joinTaskId 进群任务 ID
 * @param joinTaskResultId 进群任务明细 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议层账号 ID
 * @param commandId 当前 outbox 命令 ID
 * @param attemptNo 当前业务尝试序号，从 1 开始
 * @param outcome 统一结果码
 * @param groupJid 成功时返回的群 JID
 * @param reasonCode 失败原因码
 * @param reasonMessage 协议层失败说明，仅用于诊断
 * @param retryable 协议层给出的可重试标记
 * @param timestamp 协议事件发生时间（epoch 毫秒）
 * @param workerId 协议 worker ID
 */
public record JoinTaskResultReportedEvent(
        String eventId,
        Long tenantId,
        Long joinTaskId,
        Long joinTaskResultId,
        Long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String outcome,
        String groupJid,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long timestamp,
        String workerId
) {
}
