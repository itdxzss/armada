package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullWave;

/** 完整拉人波次的事务准备结果。 */
public record PullTaskPullWavePreparation(
        PullTaskPullWave wave,
        PullTaskPullCall call,
        PullTaskExecutionDispatchResult result) {

    /**
     * 返回可继续派发或收集的活动波次。
     *
     * @param wave 活动波次
     * @param call 派发态下一调用；收集态可为空
     * @return 准备结果
     */
    public static PullTaskPullWavePreparation ready(
            PullTaskPullWave wave, PullTaskPullCall call) {
        return new PullTaskPullWavePreparation(wave, call, null);
    }

    /**
     * 返回已经在事务内收敛的调度结果。
     *
     * @param result 调度结果
     * @return 完成结果
     */
    public static PullTaskPullWavePreparation completed(
            PullTaskExecutionDispatchResult result) {
        return new PullTaskPullWavePreparation(null, null, result);
    }

    /** @return 是否存在可继续处理的活动波次 */
    public boolean ready() {
        return wave != null;
    }
}
