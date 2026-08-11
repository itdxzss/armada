package com.armada.task.model.dto;

import java.util.List;

/** 跨租户未知协议结果扫描条件；全部业务状态由协调器传给 Mapper。 */
public record PullTaskUnknownReconciliationCriteria(
        Scope scope,
        List<Integer> executionStatuses,
        List<String> excludedReasonCodes,
        Parent parent,
        Facts facts) {

    /** 固化执行状态和排除原因范围。 */
    public PullTaskUnknownReconciliationCriteria {
        executionStatuses = List.copyOf(executionStatuses);
        excludedReasonCodes = List.copyOf(excludedReasonCodes);
        if (executionStatuses.isEmpty()) {
            throw new IllegalArgumentException("未知结果扫描的执行状态不能为空");
        }
        if (excludedReasonCodes.isEmpty()) {
            throw new IllegalArgumentException("未知结果扫描的排除原因不能为空");
        }
    }

    /** @param limit 扫描上限 @param submittedCutoff 已提交结果超时边界 */
    public record Scope(int limit, long submittedCutoff) {
    }

    /** @param taskType 父任务类型 @param taskMode 父任务模式 */
    public record Parent(String taskType, String taskMode) {
    }

    /** 各事实表的已提交和未知状态条件。 */
    public record Facts(Action action, Call call, Material material, Account account) {
    }

    /** 账号动作状态条件。 */
    public record Action(int submitted, int unknown) {
    }

    /** 批量拉人调用、attempt 与拉手可用性状态条件。 */
    public record Call(
            int submitted,
            int unknown,
            int participantAttemptSubmitted,
            int pullerAvailable) {
    }

    /** 料子入群和提权状态条件。 */
    public record Material(
            int pullSubmitted,
            int pullUnknown,
            int adminSubmitted,
            int adminUnknown) {
    }

    /** 角色账号入群和管理员权限状态条件。 */
    public record Account(
            int membershipSubmitted,
            int membershipUnknown,
            int adminSubmitted,
            int adminUnknown) {
    }
}
