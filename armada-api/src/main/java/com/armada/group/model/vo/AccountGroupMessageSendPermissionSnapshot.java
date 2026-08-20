package com.armada.group.model.vo;

/**
 * group 域向其它业务域暴露的账号群发言权限快照。
 *
 * @param accountId Armada 本地账号 ID
 * @param groupJid WhatsApp 群 JID
 * @param messageSendAllowed 当前群权限下账号是否明确可发言；事实不足时为空
 */
public record AccountGroupMessageSendPermissionSnapshot(
        Long accountId,
        String groupJid,
        Boolean messageSendAllowed) {
}
