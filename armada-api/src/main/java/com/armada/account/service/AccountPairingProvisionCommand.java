package com.armada.account.service;

/** 控台认证码配对成功后的普通自购 Web 账号落库命令。 */
public record AccountPairingProvisionCommand(
        String phone,
        Long accountGroupId,
        String remark,
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
