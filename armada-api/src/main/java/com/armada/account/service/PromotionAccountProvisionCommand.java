package com.armada.account.service;

/**
 * 推广落地页配对成功后的账号落库命令。
 *
 * <p>凭据 JSON 属于敏感数据，只允许在账号域内传递并写入 account_credential，禁止记录日志。</p>
 */
public record PromotionAccountProvisionCommand(
        String phone,
        Long promotionChannelId,
        String channelName,
        Long ownerUserId,
        String protocolAccountId,
        String protocolAddress,
        String credentialJson,
        String proxySessionId,
        String proxyCountry,
        String proxySource,
        int accountType,
        long occurredAt) {
}
