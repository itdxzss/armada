package com.armada.group.model.dto;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 任一可信来源观察到的当前群邀请码事实。
 *
 * @param observationId 观察事实 ID，用于日志关联
 * @param groupLinkId 已知群入口 ID；被动 WhatsApp 事件尚未关联时可空
 * @param groupJid WhatsApp 群 JID；已指定群入口的公开页观察可空
 * @param inviteCode 当前邀请码
 * @param protocolBackend 观察账号协议后端；按 JID 登记新入口时必填
 * @param source 观察来源
 * @param observedAt 事实观察时间(epoch 毫秒)
 */
public record GroupInviteLinkObservation(
        String observationId,
        Long groupLinkId,
        String groupJid,
        String inviteCode,
        ProtocolBackend protocolBackend,
        String source,
        Long observedAt) {
}
