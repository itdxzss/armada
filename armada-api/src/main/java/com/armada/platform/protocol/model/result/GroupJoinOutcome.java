package com.armada.platform.protocol.model.result;

/**
 * 统一的进群业务结果。
 */
public enum GroupJoinOutcome {

    /** 本次请求完成真实入群。 */
    JOINED,

    /** 请求前账号已经在群内。 */
    ALREADY_JOINED,

    /** 已提交申请，仍需群管理员审批。 */
    PENDING_APPROVAL
}
