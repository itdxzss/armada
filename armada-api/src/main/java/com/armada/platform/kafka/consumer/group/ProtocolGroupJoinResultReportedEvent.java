package com.armada.platform.kafka.consumer.group;

/**
 * Web/Android 协议层统一进群结果事件。
 *
 * <p>该模型属于 Kafka 消费边界，保留协议信封中的排查字段；任务域是否接受事件由
 * {@code joinTaskResultId + commandId + attemptNo} 联合匹配决定，迟到或重复事件不会覆盖新尝试。</p>
 *
 * @param eventId 协议事件 ID，用于日志关联
 * @param tenantId 任务所属租户 ID
 * @param joinTaskId 进群任务 ID
 * @param joinTaskResultId 本次执行对应的进群明细 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议层账号 ID，必须与事件信封账号一致
 * @param commandId 触发本次执行的 outbox 命令 ID
 * @param attemptNo 当前业务尝试序号，从 1 开始
 * @param outcome 统一结果码：JOINED、ALREADY_JOINED、PENDING_APPROVAL 或 FAILED
 * @param groupJid 成功时返回的 WhatsApp 群 JID；其它结果可为空
 * @param reasonCode 失败原因码；成功时可为空
 * @param reasonMessage 协议层失败说明，仅用于诊断
 * @param retryable 协议层是否认为该失败可重试；最终仍受任务重试配置约束
 * @param timestamp 协议事件发生时间（epoch 毫秒）；缺失时为 0
 * @param workerId 处理该命令的协议 worker ID，用于跨服务排查
 */
public record ProtocolGroupJoinResultReportedEvent(
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
