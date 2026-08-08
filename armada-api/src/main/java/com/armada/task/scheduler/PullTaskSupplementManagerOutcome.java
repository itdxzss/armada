package com.armada.task.scheduler;

import com.armada.task.model.enums.PullTaskExecutionReasonCode;

/** 补充管理员单步协议调用和实时事实复核结果。 */
public record PullTaskSupplementManagerOutcome(
        Kind kind,
        String reasonCode,
        String reasonMessage) {

    /** 入群与提权两类单步结果。 */
    public enum Kind {
        ENTRY_CONFIRMED,
        ENTRY_PENDING_APPROVAL,
        ENTRY_FAILED,
        ENTRY_UNKNOWN,
        ADMIN_CONFIRMED,
        ADMIN_FAILED,
        ADMIN_UNKNOWN
    }

    /** @return 已实时确认目标账号在群 */
    public static PullTaskSupplementManagerOutcome entryConfirmed() {
        return new PullTaskSupplementManagerOutcome(Kind.ENTRY_CONFIRMED, null, null);
    }

    /** @return 已提交入群申请，等待目标群管理员审批。 */
    public static PullTaskSupplementManagerOutcome entryPendingApproval() {
        return new PullTaskSupplementManagerOutcome(
                Kind.ENTRY_PENDING_APPROVAL,
                PullTaskExecutionReasonCode.MANAGER_JOIN_PENDING_APPROVAL.name(),
                PullTaskExecutionReasonCode.MANAGER_JOIN_PENDING_APPROVAL.message());
    }

    /** @return 入群动作明确失败 */
    public static PullTaskSupplementManagerOutcome entryFailed(String reasonCode) {
        return new PullTaskSupplementManagerOutcome(
                Kind.ENTRY_FAILED, reasonCode, "补充管理员进群失败");
    }

    /** @return 入群结果无法确认 */
    public static PullTaskSupplementManagerOutcome entryUnknown(String reasonCode) {
        return new PullTaskSupplementManagerOutcome(
                Kind.ENTRY_UNKNOWN, reasonCode, "补充管理员在群结果无法确认");
    }

    /** @return 已实时确认目标账号具备管理员权限 */
    public static PullTaskSupplementManagerOutcome adminConfirmed() {
        return new PullTaskSupplementManagerOutcome(Kind.ADMIN_CONFIRMED, null, null);
    }

    /** @return 提权或执行账号权限明确失败 */
    public static PullTaskSupplementManagerOutcome adminFailed(String reasonCode) {
        return new PullTaskSupplementManagerOutcome(
                Kind.ADMIN_FAILED, reasonCode, "补充管理员权限设置失败");
    }

    /** @return 管理员权限结果无法确认 */
    public static PullTaskSupplementManagerOutcome adminUnknown(String reasonCode) {
        return new PullTaskSupplementManagerOutcome(
                Kind.ADMIN_UNKNOWN, reasonCode, "补充管理员权限结果无法确认");
    }
}
