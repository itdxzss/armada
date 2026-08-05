package com.armada.group.model.vo;

/**
 * 存量历史群或上控后群分类候选。
 *
 * @param tenantId 租户 ID
 * @param groupLinkId 已存在群入口 ID；baseline 尚未登记时可空
 * @param groupJid WhatsApp 群 JID
 * @param groupName baseline 或群入口中的群名；可空
 * @param deletedAt 既有群入口软删除时间；活动或未登记时为空
 */
public record GroupClassificationBackfillCandidate(
        Long tenantId,
        Long groupLinkId,
        String groupJid,
        String groupName,
        Long deletedAt) {
}
