package com.armada.task.model.enums;

/** 协议层群主退群动作结果。 */
public enum PullTaskCreatorLeaveProtocolOutcome {
    /** 协议确认动作成功。 */
    SUCCESS,
    /** 协议确认动作失败。 */
    FAILED,
    /** 协议无法确认动作结果。 */
    UNKNOWN
}
