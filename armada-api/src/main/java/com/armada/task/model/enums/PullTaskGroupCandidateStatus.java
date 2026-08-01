package com.armada.task.model.enums;

/** 拉群营销候选群组当前可执行状态。 */
public enum PullTaskGroupCandidateStatus {

    /** 群组和至少一个管理账号当前可直接操作。 */
    NORMAL,

    /** 群组可进入等待池，但所有合格管理账号当前离线。 */
    WAITING_ACCOUNT_ONLINE,

    /** 只有普通成员关系，没有创建者或管理员关系。 */
    NO_ADMIN_PERMISSION,

    /** 存在管理员关系，但对应平台账号已封禁、解绑或失效。 */
    NO_ELIGIBLE_ACCOUNT,

    /** 群已被 WhatsApp 或健康检查标记封禁。 */
    GROUP_BANNED,

    /** 群邀请链接已失效。 */
    LINK_INVALID,

    /** 群健康检查明确不可用。 */
    GROUP_UNAVAILABLE,

    /** 群健康状态尚未确认。 */
    UNKNOWN,

    /** 群已被其他等待池或任务占用。 */
    OCCUPIED
}
