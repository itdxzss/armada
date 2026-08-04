package com.armada.task.model.vo;

/** 补充管理员页中的当前管理角色事实。 */
public record PullTaskManagerOptionRoleVO(
        long roleRowId,
        long accountId,
        String accountPhone,
        int membershipStatus,
        int adminStatus,
        int availabilityStatus) {
}
