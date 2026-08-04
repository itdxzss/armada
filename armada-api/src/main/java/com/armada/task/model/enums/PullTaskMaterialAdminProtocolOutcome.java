package com.armada.task.model.enums;

/** 协议层对单个 A/a 料子管理员权限变更的结果。 */
public enum PullTaskMaterialAdminProtocolOutcome {
    /** 目标已确认拥有管理员权限。 */
    SUCCESS,
    /** 权限变更已明确失败。 */
    FAILED,
    /** 动作可能发生，但目标权限尚未形成确定事实。 */
    UNKNOWN
}
