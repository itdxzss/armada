package com.armada.task.model.enums;

/** 协议层统一进群结果码。 */
public enum PullTaskManagerJoinProtocolOutcome {
    /** 已成功加入并完成在群复核。 */
    JOINED,
    /** 协议账号已在群，但本结果可能缺少可验证群 JID。 */
    ALREADY_JOINED,
    /** 入群请求等待群管理员审批。 */
    PENDING_APPROVAL,
    /** 协议明确返回失败。 */
    FAILED
}
