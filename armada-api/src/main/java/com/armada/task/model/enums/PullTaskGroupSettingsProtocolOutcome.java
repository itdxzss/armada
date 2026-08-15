package com.armada.task.model.enums;

/** 拉群单项群设置协议命令结果。 */
public enum PullTaskGroupSettingsProtocolOutcome {
    /** 协议已把该项设置的 IQ 发送完毕并被接受。 */
    SUCCESS,
    /** 协议明确执行失败。 */
    FAILED,
    /** 协议无法确认执行结果。 */
    UNKNOWN
}
