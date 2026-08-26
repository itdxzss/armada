package com.armada.group.model.vo;

/** 由协议层可靠 self-add 证据确认的上控后群分类候选。 */
public record GroupPostControlClassificationCandidate(
        Long groupLinkId,
        String groupJid,
        String groupName,
        long observedAt) {
}
