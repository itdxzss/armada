package com.armada.task.model.dto;

/** 计划态拉人调用改派参数；状态前置条件由 Java 业务层明确传入。 */
public record PullTaskCallReassignment(
        long id,
        long expectedPullerGroupAccountId,
        PullTaskPullerAssignment target,
        int expectedStatus,
        long now) {
}
