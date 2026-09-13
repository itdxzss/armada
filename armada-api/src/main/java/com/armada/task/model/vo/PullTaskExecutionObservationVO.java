package com.armada.task.model.vo;

/**
 * 独立于任务状态和业务阶段的运行说明，所有时间均来自当前只读快照。
 *
 * @param state 观察状态，仅供展示，不能作为执行指令
 * @param label 简短运行说明
 * @param detail 当前等待或异常的事实依据
 * @param nextStep 后续动作说明，不代表已经执行
 * @param observedAt 服务端取样时间
 * @param waitStartedAt 可证明的等待起点；没有记录时为空
 * @param nextCheckAt 下一次允许调度检查的时间，不承诺届时执行成功
 * @param nextDispatchAt 波次下一批最早可派发时间
 * @param waveNo 当前活动波次号
 * @param callSeq 当前波次内未完成调用序号
 * @param plannedCallCount 当前波次冻结批次数
 */
public record PullTaskExecutionObservationVO(
        String state, String label, String detail, String nextStep,
        long observedAt, Long waitStartedAt, Long nextCheckAt, Long nextDispatchAt,
        Integer waveNo, Integer callSeq, Integer plannedCallCount) {
}
