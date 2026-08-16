package com.armada.group.model.vo;

/** 群入口解析到的新模型当前群标识。 */
public record GroupCurrentIdentity(
        Long groupLinkId,
        String groupJid,
        String inviteCode) {
}
