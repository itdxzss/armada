package com.armada.group.model.vo;

/**
 * Mapper 批量查询返回的账号群发言权限投影。
 *
 * @param accountId Armada 本地账号 ID
 * @param groupJid WhatsApp 群 JID
 * @param messageSendAllowed 当前群权限下账号是否明确可发言；事实不足时为空
 */
public record AccountGroupMessageSendPermissionRow(
        Long accountId,
        String groupJid,
        Boolean messageSendAllowed) {
}
