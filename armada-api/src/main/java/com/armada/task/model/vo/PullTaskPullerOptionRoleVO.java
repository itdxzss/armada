package com.armada.task.model.vo;

/** 补充拉手页中的当前拉手事实。 */
public record PullTaskPullerOptionRoleVO(
        long roleRowId,
        long accountId,
        String accountPhone,
        int membershipStatus,
        int availabilityStatus,
        boolean occupied) {
}
