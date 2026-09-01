package com.armada.account.model.vo;

/** 指定账号分组的普通拉群拉手限制汇总。 */
public record AccountPullerRestrictionSummary(
        long serverNow,
        int restrictedCount,
        Long nextRestrictionUntil) {
}
