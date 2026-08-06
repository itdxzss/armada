package com.armada.task.model.enums;

/** 任务管理员提权协议命令结果。 */
public enum PullTaskManagerAdminProtocolOutcome {
    /** 协议明确执行成功，仍需实时权限确认。 */
    SUCCESS,
    /** 协议明确执行失败。 */
    FAILED,
    /** 协议无法确认执行结果。 */
    UNKNOWN
}
