package com.armada.group.model.dto;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * group 域接收的当前群邀请码事实。
 *
 * @param eventId 协议事件 ID
 * @param groupJid WhatsApp 群 JID
 * @param inviteCode 当前邀请码
 * @param protocolBackend 观察事件的协议后端
 * @param occurredAt 事实发生时间(epoch 毫秒)
 */
public record GroupInviteLinkChangedEvent(
        String eventId,
        String groupJid,
        String inviteCode,
        ProtocolBackend protocolBackend,
        Long occurredAt) {
}
