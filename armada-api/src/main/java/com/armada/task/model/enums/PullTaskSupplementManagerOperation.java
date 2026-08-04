package com.armada.task.model.enums;

/** 补充管理员检查点当前要执行的协议动作。 */
public enum PullTaskSupplementManagerOperation {
    /** 候选账号自行踩链接。 */
    JOIN_BY_LINK,
    /** 冻结的当前管理员邀请候选账号。 */
    MANAGER_INVITE,
    /** 当前管理员把已入群候选提升为管理员。 */
    PROMOTE_ADMIN
}
