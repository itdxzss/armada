package com.armada.group.model.vo;

/**
 * 创建 WhatsApp 群逐成员结果。
 *
 * @param jid       成员 WhatsApp JID
 * @param status    归一化业务状态
 * @param rawStatus WhatsApp/Baileys 原始状态码
 */
public record GroupCreateParticipantVO(
        String jid,
        String status,
        String rawStatus
) {
}
