package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 协议层建群结果。
 *
 * @param groupJid 新建 WhatsApp 群 JID
 * @param partial  是否存在未完整回执的成员结果
 * @param results  逐成员加入结果
 */
public record GroupCreateResult(
        String groupJid,
        boolean partial,
        List<GroupCreateParticipantResult> results
) {
}
