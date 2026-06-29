package com.armada.group.model.dto;

import java.util.List;

/**
 * 账号当前群列表回报事件。
 *
 * @param tenantId          租户 ID
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议账号句柄,仅用于日志
 * @param reportedAt        协议层同步时间(epoch 毫秒),可空
 * @param groups            协议层返回的账号当前参与群列表
 * @param eventId           协议层事件 ID,仅用于日志
 */
public record AccountGroupsReportedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        Long reportedAt,
        List<Group> groups,
        String eventId
) {

    /**
     * 账号当前参与的单个群。
     *
     * @param groupJid     WhatsApp 群 JID
     * @param subject      群名称,可空
     * @param memberCount  群人数,可空
     * @param ownerJid     群主 JID,可空
     * @param ownerPhone   群主号码,可空
     * @param admin        当前账号是否管理员,可空
     * @param announceOnly 是否仅管理员发言,可空
     * @param avatarUrl    群头像 URL,可空
     */
    public record Group(
            String groupJid,
            String subject,
            Integer memberCount,
            String ownerJid,
            String ownerPhone,
            Boolean admin,
            Boolean announceOnly,
            String avatarUrl
    ) {
    }
}
