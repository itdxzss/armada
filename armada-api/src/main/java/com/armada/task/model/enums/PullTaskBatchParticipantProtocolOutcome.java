package com.armada.task.model.enums;

/** 协议批量加成员命令中单个成员的明确或未知结果。 */
public enum PullTaskBatchParticipantProtocolOutcome {

    /** 协议确认成员已加入群。 */
    SUCCESS,

    /** 协议确认成员加入失败。 */
    FAILED,

    /** 协议未能确认成员是否已加入群。 */
    UNKNOWN
}
