package com.armada.task.model.dto;

/** 以 queryId、commandId 和当前状态 CAS 收敛一次成员查询。 */
public record PullTaskMemberQuerySettlement(
        long queryId,
        String commandId,
        int expectedStatus,
        int targetStatus,
        String resultJson,
        String errorCode,
        String errorMessage,
        long completedAt
) {
}
