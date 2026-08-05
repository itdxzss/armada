package com.armada.platform.kafka.consumer.account;

/**
 * 协议层请求 Armada 同步单群详情的稳定事件。
 *
 * @param eventId 协议事件 ID
 * @param tenantId 租户 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param groupJid 群 JID
 * @param trigger 触发类型
 * @param occurredAt 协议事实时间(epoch 毫秒)
 * @param source 协议事件来源
 * @param workerId 协议 worker ID
 */
public record ProtocolGroupMetadataSyncRequestedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String trigger,
        Long occurredAt,
        String source,
        String workerId) {
}
