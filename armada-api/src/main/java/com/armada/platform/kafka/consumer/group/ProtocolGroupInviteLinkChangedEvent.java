package com.armada.platform.kafka.consumer.group;

/**
 * 协议层观察到的群邀请链接变更事件。
 *
 * @param eventId 协议事件 ID
 * @param tenantId 租户 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param protocolBackend 协议后端
 * @param groupJid WhatsApp 群 JID
 * @param inviteCode 当前邀请码
 * @param author 可选操作人 JID
 * @param source 协议事件来源
 * @param occurredAt 事实发生时间(epoch 毫秒)
 * @param workerId 协议 worker ID
 */
public record ProtocolGroupInviteLinkChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        String groupJid,
        String inviteCode,
        String author,
        String source,
        Long occurredAt,
        String workerId) {
}
