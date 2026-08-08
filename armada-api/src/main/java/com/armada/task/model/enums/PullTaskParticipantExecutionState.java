package com.armada.task.model.enums;

/** 批量拉人逐号码结果相对 WhatsApp 副作用调用的执行阶段。 */
public enum PullTaskParticipantExecutionState {

    /** 号码尚未提交给协议调用，可以直接释放并重新拉取。 */
    NOT_STARTED,

    /** 协议已返回该号码的明确成功或失败结果。 */
    STARTED,

    /** 号码已提交协议调用，但最终结果无法确认。 */
    UNCERTAIN
}
