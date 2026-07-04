package com.armada.platform.kafka.consumer.message;

/**
 * 协议层消息发送结果事件。
 *
 * <p>该事件由协议 worker 在执行 {@code message.send.requested} 后发布。成功事件带
 * WhatsApp {@code messageId};失败事件带 {@code reasonCode/reasonMessage}。API 侧用
 * {@code attemptId} 做幂等回写,用 {@code commandId} 做排查关联。</p>
 *
 * @param eventId         协议事件 ID
 * @param tenantId        租户 ID
 * @param marketingTaskId 营销任务 ID
 * @param targetId        营销任务目标 ID
 * @param attemptId       发送尝试 ID
 * @param roundNo         营销轮次号
 * @param protocolAccountId 协议层账号句柄
 * @param groupJid        WhatsApp 群 JID
 * @param commandId       协议 outbox 命令 ID
 * @param success         是否发送成功
 * @param messageId       WhatsApp message id;失败时为空
 * @param reasonCode      失败原因码;成功时为空
 * @param reasonMessage   失败原因描述;成功时为空
 * @param timestamp       协议层结果时间(epoch毫秒)
 * @param workerId        处理该命令的协议 worker
 */
public record ProtocolMessageSendResultReportedEvent(
        String eventId,
        Long tenantId,
        Long marketingTaskId,
        Long targetId,
        Long attemptId,
        Long roundNo,
        String protocolAccountId,
        String groupJid,
        String commandId,
        boolean success,
        String messageId,
        String reasonCode,
        String reasonMessage,
        Long timestamp,
        String workerId
) {
}
