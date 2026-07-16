package com.armada.task.model.enums;

/**
 * 进群任务明细的异步派发状态。
 *
 * <p>该状态与业务结果状态共同组成状态机：PENDING+WAITING 可被调度，PENDING+SUBMITTED 等待协议结果，
 * SUCCESS/FAILED+TERMINAL 已结束。拆分两个维度是为了不把 Kafka 传输过程伪装成业务成功或失败。</p>
 */
public enum JoinTaskDispatchState {
    /** 等待到期调度；只有当前账号 lane 的头部明细会设置 next_execute_at。 */
    WAITING,

    /** outbox 已在同一事务内写入，等待 Kafka 发布及协议结果。 */
    SUBMITTED,

    /** 明细已进入业务成功或失败终态，不再参与调度。 */
    TERMINAL
}
