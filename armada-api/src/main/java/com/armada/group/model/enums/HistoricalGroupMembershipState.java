package com.armada.group.model.enums;

/**
 * 历史群相对操作账号当前群列表的成员关系状态。
 */
public enum HistoricalGroupMembershipState {

    /** 尚未手动刷新,只有 baseline 静态事实。 */
    UNVERIFIED,

    /** 最近一次请求级刷新确认操作账号当前仍在群内。 */
    CURRENT_IN_GROUP,

    /** 最近一次请求级刷新确认操作账号当前已不在群内。 */
    CURRENT_NOT_IN_GROUP,

    /** 当前群轻量列表整体获取失败,不能据此判断是否退出。 */
    FETCH_FAILED
}
