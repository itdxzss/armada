package com.armada.group.model.vo;

/**
 * 单条群链接实时预览结果。
 *
 * @param groupLinkId  群链接 ID
 * @param url          群邀请链接
 * @param success      是否预览成功
 * @param reason       失败原因;成功时为空
 * @param groupJid     WhatsApp 群 JID
 * @param waSubject    WhatsApp 群名称
 * @param memberSize   成员数
 * @param banned       是否封禁
 * @param ownerPhone   群主号码
 * @param announceOnly 是否仅管理员发言
 * @param inviteCode   邀请码
 * @param previewAt    预览成功时间(epoch毫秒)
 */
public record GroupLinkPreviewItemVO(
        Long groupLinkId,
        String url,
        boolean success,
        String reason,
        String groupJid,
        String waSubject,
        Integer memberSize,
        Boolean banned,
        String ownerPhone,
        Boolean announceOnly,
        String inviteCode,
        Long previewAt) {
}
