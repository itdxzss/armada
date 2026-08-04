package com.armada.task.model.enums;

/** 协议回调能够明确确认的终态；未知结果由缺失项或状态查询表达。 */
public enum PullTaskProtocolOutcome {
    /** 协议明确成功。 */
    SUCCESS,
    /** 协议明确失败。 */
    FAILED
}
