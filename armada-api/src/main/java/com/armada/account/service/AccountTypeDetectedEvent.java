package com.armada.account.service;

/**
 * account 域接收的账号类型协议检测事实。
 *
 * <p>waCreationAt / businessMemberSinceAt 为 epoch 毫秒,协议未提供时为 null,只用于首次写入号质量分析时间。
 */
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
        String eventId,
        Long waCreationAt,
        Long businessMemberSinceAt
) {
}
