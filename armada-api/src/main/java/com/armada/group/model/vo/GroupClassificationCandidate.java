package com.armada.group.model.vo;

/**
 * 历史群与上控后群分类候选。
 *
 * @param groupLinkId 群入口 ID；首次 baseline 尚未登记时可空
 * @param groupJid WhatsApp 群 JID
 * @param groupName 协议观察到的群名；可空
 */
public record GroupClassificationCandidate(
        Long groupLinkId,
        String groupJid,
        String groupName) {
}
