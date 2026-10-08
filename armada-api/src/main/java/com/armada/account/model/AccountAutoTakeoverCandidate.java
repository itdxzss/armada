package com.armada.account.model;

/** 跨租户扫描发现的被抢登或抢登中离线账号，仅携带恢复路由所需状态。 */
public record AccountAutoTakeoverCandidate(Long tenantId, Long accountId, Integer accountState) {
}
