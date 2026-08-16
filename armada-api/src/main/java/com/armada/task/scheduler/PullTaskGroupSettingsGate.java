package com.armada.task.scheduler;

/**
 * 群设置门控结果：加人权限是否已确认放开。
 *
 * <p>放开加人权限是拉手 add 料子的硬前置，因此它是本阶段唯一的推进条件。未满足时执行行返回
 * 等待，绝不提前占用拉手——拉手是稀缺资源，占在一条注定失败的执行行上会拖垮整个任务。</p>
 *
 * @param satisfied 加人权限是否已确认
 * @param result 未满足时的调度结果
 */
public record PullTaskGroupSettingsGate(
        boolean satisfied,
        PullTaskExecutionDispatchResult result) {

    /** @return 加人权限已确认，门打开，可以继续占用拉手 */
    public static PullTaskGroupSettingsGate open() {
        return new PullTaskGroupSettingsGate(true, null);
    }

    /** @return 命令已提交或仍在等待结果，本轮到此为止 */
    public static PullTaskGroupSettingsGate waiting(PullTaskExecutionDispatchResult result) {
        return new PullTaskGroupSettingsGate(false, result);
    }
}
