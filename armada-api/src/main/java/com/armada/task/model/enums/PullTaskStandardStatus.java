package com.armada.task.model.enums;

/** 现有普通拉群任务状态。 */
public enum PullTaskStandardStatus {

    /** 待开始。 */
    WAIT_START,

    /** 执行中。 */
    EXECUTING,

    /** 已暂停。 */
    PAUSED,

    /** 已中断。 */
    INTERRUPTED,

    /** 已完成。 */
    COMPLETED,

    /** 已结束。 */
    ENDED
}
