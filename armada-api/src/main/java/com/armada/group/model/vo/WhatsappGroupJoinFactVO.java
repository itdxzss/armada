package com.armada.group.model.vo;

/** 营销导出可读取的 WhatsApp 最近一次进群事实。 */
public record WhatsappGroupJoinFactVO(
        String groupJid,
        String participantJid,
        String phone,
        Long joinedAt) {
}
