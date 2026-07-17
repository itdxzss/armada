package com.armada.group.model.dto;

import java.util.List;

/**
 * 固定操作账号对单个历史群执行批量成员动作的请求。
 *
 * @param accountId       固定操作账号 ID
 * @param groupJid        baseline 群 JID
 * @param participantJids 按用户选择顺序提交的目标成员 JID
 */
public record HistoricalGroupParticipantActionDTO(
        Long accountId,
        String groupJid,
        List<String> participantJids) {
}
