package com.armada.group.model.dto;

/**
 * 群链接健康检测回报事件。
 *
 * @param tenantId          租户 ID
 * @param groupLinkId       group_link.id
 * @param groupJid          WhatsApp 群 JID
 * @param health            协议层健康状态
 * @param memberCount       当前成员数,可空
 * @param checkedAt         检测时间(epoch 毫秒),可空
 * @param errorCode         失败原因码,可空
 * @param protocolAccountId 执行检测的协议账号句柄,仅用于日志
 * @param eventId           协议层事件 ID,仅用于日志
 */
public record GroupLinkHealthReportedEvent(
        Long tenantId,
        Long groupLinkId,
        String groupJid,
        String health,
        Integer memberCount,
        Long checkedAt,
        String errorCode,
        String protocolAccountId,
        String eventId) {
}
