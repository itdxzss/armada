package com.armada.account.model.vo;

/** 账号作为普通拉群拉手的当前限制快照。 */
public record AccountPullerRestrictionSnapshot(
        long accountId,
        int status,
        Long restrictionUntil,
        String restrictionReasonCode) {
}
