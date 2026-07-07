package com.armada.group.model.vo;

import java.util.List;

/**
 * 创建 WhatsApp 群响应。
 *
 * @param groupJid 新建群 JID
 * @param partial  是否存在未完整回执
 * @param results  逐成员结果
 */
public record GroupCreateVO(
        String groupJid,
        Boolean partial,
        List<GroupCreateParticipantVO> results
) {
}
