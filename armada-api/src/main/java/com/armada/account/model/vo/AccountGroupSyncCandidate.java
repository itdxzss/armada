package com.armada.account.model.vo;

/**
 * 账号当前群同步候选。
 *
 * @param tenantId          租户 ID
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 */
public record AccountGroupSyncCandidate(
        Long tenantId,
        Long accountId,
        String protocolAccountId
) {
}
