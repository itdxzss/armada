package com.armada.task.model.enums;

/** 拉群营销任务状态。 */
public enum PullTaskMarketingStatus {

    /** 草稿。 */
    DRAFT,

    /** 待开始。 */
    WAIT_START,

    /** 校验中。 */
    VALIDATING,

    /** 等待资源。 */
    WAITING_RESOURCE,

    /** 执行中。 */
    EXECUTING,

    /** 部分完成。 */
    PARTIAL_COMPLETED,

    /** 已暂停。 */
    PAUSED,

    /** 已停止。 */
    STOPPED,

    /** 已完成。 */
    COMPLETED,

    /** 执行失败。 */
    FAILED
}
