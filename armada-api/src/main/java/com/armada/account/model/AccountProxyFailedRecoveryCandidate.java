package com.armada.account.model;

/** 跨租户后台扫描得到的 PROXY_FAILED 持续恢复候选。 */
public record AccountProxyFailedRecoveryCandidate(Long tenantId, Long ownerUserId, Long accountId) {
}
