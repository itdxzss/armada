package com.armada.task.model.dto;

/** 拉人调用选择的拉手角色行和账号。 */
public record PullTaskPullerAssignment(
        long groupAccountId,
        long accountId) {
}
