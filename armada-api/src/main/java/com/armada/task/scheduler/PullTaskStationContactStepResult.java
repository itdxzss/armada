package com.armada.task.scheduler;

/** 一次拉人调用在拉手—站台联系人子步骤中的结果。 */
public enum PullTaskStationContactStepResult {
    /** 尚有联系人方向未执行，本轮已释放执行行租约。 */
    MORE_CONTACTS,
    /** 本次调用的全部联系人方向已终态，可继续批量拉人。 */
    CALL_READY,
    /** 执行行租约、调用或动作事实已变化。 */
    LOST
}
