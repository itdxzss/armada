package com.armada.group.model.vo;

/**
 * 账号群快照中的稳定兼容句柄。
 *
 * @param groupJid WhatsApp 群 JID
 * @param groupLinkId 稳定兼容句柄 ID
 * @param linkUrl 兼容句柄 URL
 */
public record AccountObservedGroupHandle(String groupJid, Long groupLinkId, String linkUrl) {
}
