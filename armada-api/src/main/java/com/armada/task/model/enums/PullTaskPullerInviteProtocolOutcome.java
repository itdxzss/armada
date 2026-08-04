package com.armada.task.model.enums;

/** 管理员邀请单个拉手的协议结果。 */
public enum PullTaskPullerInviteProtocolOutcome {
    /** 协议确认目标已加入群。 */
    SUCCESS,
    /** 协议明确返回邀请失败。 */
    FAILED,
    /** 超时或缺少逐成员回执，结果不能确认。 */
    UNKNOWN
}
