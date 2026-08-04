package com.armada.task.scheduler;

/** 单条执行行在一轮普通群链接调度中的结果。 */
public enum PullTaskExecutionDispatchResult {
    /** 当前阶段完成并推进到下一阶段。 */
    ADVANCED,
    /** 当前执行行进入失败终态。 */
    FAILED,
    /** 保留当前检查点延后重试或等待资源。 */
    DEFERRED,
    /** 乐观锁或租约已变化，本 worker 放弃回写。 */
    LOST
}
