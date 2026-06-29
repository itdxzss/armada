package com.armada.platform.kafka.consumer.group;

/**
 * 协议群组健康检测 Kafka 事件。
 *
 * <p>顶层 {@code accountId} 对应 Armada 的 {@code protocol_account_id};
 * {@code tenantId/groupLinkId/groupJid/health/memberCount} 来自 envelope.data。</p>
 *
 * @param eventId           协议层事件 ID,用于日志排查和后续幂等
 * @param tenantId          租户 ID
 * @param groupLinkId       Armada group_link.id
 * @param groupJid          WhatsApp 群 JID
 * @param health            协议层健康状态
 * @param memberCount       当前成员数,可空
 * @param checkedAt         协议层检测时间(epoch 毫秒)
 * @param errorCode         失败原因码,可空
 * @param subject           群名称,可空
 * @param protocolAccountId 执行检测的协议账号句柄
 * @param workerId          产生事件的协议层 worker ID
 */
public record ProtocolGroupHealthReportedEvent(
        String eventId,
        Long tenantId,
        Long groupLinkId,
        String groupJid,
        String health,
        Integer memberCount,
        Long checkedAt,
        String errorCode,
        String subject,
        String protocolAccountId,
        String workerId) {
}
