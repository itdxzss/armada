package com.armada.platform.protocol.model.result;

/**
 * 建群时协议层返回的逐成员结果。
 *
 * @param jid       成员 WhatsApp JID
 * @param status    归一化业务状态,如 OK / PRIVACY_BLOCKED / ALREADY_IN
 * @param rawStatus WhatsApp/Baileys 原始状态码
 */
public record GroupCreateParticipantResult(
        String jid,
        String status,
        String rawStatus
) {
}
