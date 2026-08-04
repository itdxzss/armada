package com.armada.platform.kafka.consumer.group;

/**
 * 协议层群动作结果事件。
 *
 * @param eventId 协议事件 ID
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 账号动作 ID
 * @param source 命令业务来源
 * @param operation 协议动作类型
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId Outbox 命令 ID
 * @param attemptNo 尝试序号
 * @param outcome 结果：SUCCESS 或 FAILED
 * @param targetJid 成员动作目标 JID；联系人保存结果为空
 * @param reasonCode 原因码
 * @param reasonMessage 脱敏原因说明
 * @param retryable 协议层重试建议；任务是否重试仍由业务规则决定
 * @param timestamp 协议动作发生时间，epoch 毫秒
 * @param workerId 协议 worker ID
 */
public record ProtocolGroupActionResultReportedEvent(
        String eventId,
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        String source,
        String operation,
        Long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String outcome,
        String targetJid,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long timestamp,
        String workerId
) {
}
