package com.armada.task.model.dto;

/** 账号动作从指定前置状态 CAS 为已提交。 */
public record PullTaskActionSubmission(
        long actionId,
        int expectedStatus,
        int targetStatus,
        String commandId,
        long now) {
}
