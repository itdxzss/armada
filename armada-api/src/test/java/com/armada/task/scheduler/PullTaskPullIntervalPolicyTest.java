package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.task.model.entity.PullTaskStandardSetting;
import org.junit.jupiter.api.Test;

/** 拉人间隔闭区间与历史固定值的确定性边界测试。 */
class PullTaskPullIntervalPolicyTest {

    @Test
    void samplesBothInclusiveBounds() {
        PullTaskStandardSetting setting = range(5, 30);
        assertThat(PullTaskPullIntervalPolicy.nextSubmissionAt(setting, 1_000L, (min, max) -> min))
                .isEqualTo(6_000L);
        assertThat(PullTaskPullIntervalPolicy.nextSubmissionAt(setting, 1_000L, (min, max) -> max))
                .isEqualTo(31_000L);
        assertThat(PullTaskPullIntervalPolicy.nextSubmissionAt(range(0, 0), 1_000L,
                (min, max) -> { throw new AssertionError("固定间隔不应重新采样"); }))
                .isEqualTo(1_000L);
    }

    @Test
    void missingMaximumKeepsLegacyFixedIntervalWithoutDrawingRandomness() {
        assertThat(PullTaskPullIntervalPolicy.nextSubmissionAt(range(30, null), 1_000L,
                (min, max) -> { throw new AssertionError("固定间隔不应重新采样"); }))
                .isEqualTo(31_000L);
    }

    @Test
    void rejectsAnInvalidRangeOrRandomValue() {
        assertThatThrownBy(() -> PullTaskPullIntervalPolicy.nextSubmissionAt(
                range(15, 10), 1_000L, (min, max) -> min)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PullTaskPullIntervalPolicy.nextSubmissionAt(
                range(10, 15), 1_000L, (min, max) -> 9)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyRecoveryUsesConservativeUpperBoundWithoutNewRandomSampling() {
        assertThat(PullTaskPullIntervalPolicy.latestPossibleDeadline(range(10, 15), 1_000L))
                .isEqualTo(16_000L);
        assertThat(PullTaskPullIntervalPolicy.latestPossibleDeadline(range(30, null), 1_000L))
                .isEqualTo(31_000L);
    }

    private static PullTaskStandardSetting range(int minimum, Integer maximum) {
        PullTaskStandardSetting setting = new PullTaskStandardSetting();
        setting.setPullIntervalSeconds(minimum);
        setting.setPullIntervalMaxSeconds(maximum);
        return setting;
    }
}
