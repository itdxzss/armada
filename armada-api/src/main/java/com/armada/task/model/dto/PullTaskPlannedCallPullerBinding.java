package com.armada.task.model.dto;

/** 把计划调用及其全部计划 attempt 绑定到同一拉手代际。 */
public record PullTaskPlannedCallPullerBinding(
        Scope scope,
        Target target,
        long now) {

    /** 调用定位、当前拉手与前置状态。 */
    public record Scope(
            long pullCallId,
            Long expectedPullerGroupAccountId,
            int expectedCallStatus) {
    }

    /** 真实拉手角色、账号与分配代际。 */
    public record Target(
            long pullerGroupAccountId,
            long pullerAccountId,
            long assignmentSeq) {
    }

    /** 校验 Mapper 必要参数。 */
    public PullTaskPlannedCallPullerBinding {
        if (scope == null || target == null || target.assignmentSeq() <= 0) {
            throw new IllegalArgumentException("计划调用拉手绑定参数不完整");
        }
    }
}
