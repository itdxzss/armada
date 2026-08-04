package com.armada.task.model.vo;

/** 执行行内一个管理、拉手或站台角色事实。 */
public record PullTaskStandardRoleVO(
        long roleRowId,
        long accountId,
        String accountPhone,
        int roleType,
        int roleSeq,
        int membershipStatus,
        String membershipReasonCode,
        String membershipReasonMessage,
        Long membershipResultAt,
        int availabilityStatus,
        String unavailableReasonCode,
        Long pullCallId) {
}
