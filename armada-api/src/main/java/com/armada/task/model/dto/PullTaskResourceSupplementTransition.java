package com.armada.task.model.dto;

import java.util.List;

/** 资源补充确认后，由 Java 传入全部前置态和目标态执行单行 CAS。 */
public record PullTaskResourceSupplementTransition(
        Scope scope,
        Expected expected,
        Target target) {

    /** @param taskId 父任务 ID @param executionId 执行行 ID @param expectedVersion 版本 @param now 时间 */
    public record Scope(long taskId, long executionId, int expectedVersion, long now) {
    }

    /** @param executionStatuses 前置执行状态 @param waitResourceType 等待类型 @param stages 前置阶段 */
    public record Expected(
            List<Integer> executionStatuses,
            int waitResourceType,
            List<Integer> stages) {
        public Expected {
            executionStatuses = List.copyOf(executionStatuses);
            stages = List.copyOf(stages);
        }
    }

    /** @param executionStatus 目标状态 @param stage 目标检查点 */
    public record Target(int executionStatus, int stage) {
    }
}
