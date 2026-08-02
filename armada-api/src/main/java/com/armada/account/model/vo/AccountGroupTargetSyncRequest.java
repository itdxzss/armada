package com.armada.account.model.vo;

/**
 * 营销导出前需要定向刷新的 WhatsApp 群。
 *
 * @param accountId 可访问该群的协议观察账号 ID
 * @param groupJid  任务实际涉及的 WhatsApp 群 JID
 */
public record AccountGroupTargetSyncRequest(Long accountId, String groupJid) {
}
