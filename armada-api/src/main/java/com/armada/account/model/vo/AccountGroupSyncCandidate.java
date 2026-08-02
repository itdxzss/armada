package com.armada.account.model.vo;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 账号当前群同步候选。
 *
 * @param tenantId          租户 ID
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 * @param protocolId        账号协议标识
 * @param phone             WhatsApp 号码
 */
public record AccountGroupSyncCandidate(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolId,
        String phone
) {

    /** @return 统一协议后端枚举 */
    public ProtocolBackend protocolBackend() {
        return ProtocolBackend.fromProtocolId(protocolId);
    }
}
