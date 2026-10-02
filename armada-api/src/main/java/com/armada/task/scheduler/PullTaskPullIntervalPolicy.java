package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskStandardSetting;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntBinaryOperator;

/** 同群相邻料子命令提交的闭区间随机间隔；计算后的绝对时间由波次事务冻结。 */
final class PullTaskPullIntervalPolicy {

    private static final long MILLIS_PER_SECOND = 1_000L;

    private PullTaskPullIntervalPolicy() {
    }

    /** 提交料子命令时采样一次，重试与恢复直接沿用已保存的绝对时间。 */
    static long nextSubmissionAt(PullTaskStandardSetting setting, long now) {
        return nextSubmissionAt(setting, now, (minimum, maximum) ->
                (int) ThreadLocalRandom.current().nextLong(minimum, (long) maximum + 1L));
    }

    /** 注入闭区间随机源以验证边界；生产调用只使用线程本地随机源。 */
    static long nextSubmissionAt(
            PullTaskStandardSetting setting, long now, IntBinaryOperator random) {
        int minimum = setting.getPullIntervalSeconds() == null ? 0 : setting.getPullIntervalSeconds();
        int maximum = setting.getPullIntervalMaxSeconds() == null ? minimum : setting.getPullIntervalMaxSeconds();
        if (minimum < 0 || maximum < minimum) {
            throw new IllegalArgumentException("拉人间隔必须为有序非负区间");
        }
        int seconds = minimum == maximum ? minimum : random.applyAsInt(minimum, maximum);
        if (seconds < minimum || seconds > maximum) {
            throw new IllegalStateException("随机拉人间隔越界");
        }
        return Math.addExact(now, Math.multiplyExact((long) seconds, MILLIS_PER_SECOND));
    }

    /** 仅用于缺失波次检查点的旧任务恢复；取上限防止恢复时缩短已发生调用的间隔。 */
    static long latestPossibleDeadline(PullTaskStandardSetting setting, long submittedAt) {
        return nextSubmissionAt(setting, submittedAt, (minimum, maximum) -> maximum);
    }
}
