package com.armada.account.selection.model;

/**
 * 圈号命中的一个账号及其协议事实快照。
 *
 * @param accountId Armada 账号主键
 * @param wsPhone WhatsApp 号码
 * @param protocolId 接入协议标识，决定发往 Web 还是 Android 后端
 * @param protocolAccountId 协议账号句柄
 */
public record SelectedAccount(
        Long accountId,
        String wsPhone,
        String protocolId,
        String protocolAccountId
) {
}
