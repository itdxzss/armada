package com.armada.group.model.dto;

import java.util.List;

/**
 * 账号组历史群批量成员动作请求。
 *
 * @param accountGroupId  来源账号组 ID
 * @param groupJid        历史群 JID
 * @param participantJids 按用户选择顺序提交的目标成员 JID
 */
public record HistoricalGroupParticipantActionDTO(
        Long accountGroupId,
        String groupJid,
        List<String> participantJids) {
}
