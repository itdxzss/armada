package com.armada.platform.kafka.consumer.group;

/**
 * 普通链接拉群的一次批量加成员命令中，单个成员的协议结果事件。
 *
 * @param eventId Kafka 事件幂等 ID
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 执行行 ID
 * @param pullCallId 批量拉人调用 ID
 * @param accountId 执行本次调用的拉手账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId 协议命令 ID
 * @param attemptNo 协议尝试次数
 * @param targetJid 本次回写的成员 JID
 * @param outcome 单成员结果：SUCCESS、FAILED 或 UNKNOWN
 * @param executionState 号码相对协议副作用调用的执行阶段
 * @param reasonCode 结果原因码
 * @param reasonMessage 已脱敏原因描述
 * @param retryable 是否可重试
 * @param timestamp 协议结果时间(epoch 毫秒)
 * @param workerId 协议 worker ID
 */
public record ProtocolPullTaskBatchParticipantResultReportedEvent(
        String eventId,
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long pullCallId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        String outcome,
        String executionState,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long timestamp,
        String workerId) {
}
