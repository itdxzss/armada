package com.armada.task.model.dto;

/** 执行行粘性拉手身份和分配代际的一次 CAS 转换。 */
public record PullTaskStickyPullerTransition(
        Scope scope,
        Target target,
        long now) {

    /** 当前粘性拉手身份与代际。 */
    public record Scope(
            long executionId,
            Long expectedPullerGroupAccountId,
            long expectedAssignmentSeq) {
    }

    /** 新粘性拉手、下一轮询游标与新代际。 */
    public record Target(
            Long pullerGroupAccountId,
            long assignmentSeq,
            int nextPullerIndex) {
    }

    /** 校验换号只消耗一个新代际。 */
    public PullTaskStickyPullerTransition {
        if (scope == null || target == null) {
            throw new IllegalArgumentException("粘性拉手转换参数不能为空");
        }
        if (target.pullerGroupAccountId() == null
                || target.assignmentSeq() != scope.expectedAssignmentSeq() + 1) {
            throw new IllegalArgumentException("新拉手必须使用下一分配代际");
        }
    }
}
