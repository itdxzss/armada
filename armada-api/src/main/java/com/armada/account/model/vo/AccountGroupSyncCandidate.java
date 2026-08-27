package com.armada.account.model.vo;

/**
 * 账号当前群同步候选。
 *
 * @param tenantId          租户 ID
 * @param ownerUserId       账号数据 owner
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 * @param protocolBackend   协议后端 WEB 或 ANDROID，决定同步命令发往哪个 topic
 */
public record AccountGroupSyncCandidate(
        Long tenantId,
        Long ownerUserId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend
) {
}
