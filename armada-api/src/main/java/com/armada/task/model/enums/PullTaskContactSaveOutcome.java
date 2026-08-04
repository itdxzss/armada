package com.armada.task.model.enums;

/** 协议联系人保存结果。 */
public enum PullTaskContactSaveOutcome {
    /** 协议确认联系人已保存。 */
    SUCCESS,
    /** 协议明确返回本次保存失败。 */
    FAILED,
    /** 协议调用结果不明确；作为独立终态保留且不自动重试。 */
    UNKNOWN
}
