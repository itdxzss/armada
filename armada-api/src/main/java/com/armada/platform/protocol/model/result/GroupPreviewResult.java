package com.armada.platform.protocol.model.result;

import java.time.Instant;

/**
 * 群邀请链接实时预览结果。
 *
 * @param groupJid    WhatsApp 群 JID
 * @param subject     WhatsApp 真实群名
 * @param memberCount 成员数快照
 * @param banned      是否封禁
 * @param ownerJid    群主 JID
 * @param desc        群描述
 * @param announce    是否仅管理员发言
 * @param restrict    是否锁群
 * @param inviteCode  邀请码
 * @param previewAt   协议层预览时间
 */
public record GroupPreviewResult(
        String groupJid,
        String subject,
        Integer memberCount,
        boolean banned,
        String ownerJid,
        String desc,
        Boolean announce,
        Boolean restrict,
        String inviteCode,
        Instant previewAt) {
}
