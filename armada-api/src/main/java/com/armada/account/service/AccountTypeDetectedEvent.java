package com.armada.account.service;

/** account 域接收的账号类型协议检测事实。 */
public record AccountTypeDetectedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String onlineAttemptId,
        String commandId,
        String protocolBackend,
        Long credentialVersion,
        Integer declaredAccountType,
        String detectedAccountType,
        String verificationLevel,
        String source,
        Long detectedAt,
        String eventId
) {
}
