package com.armada.task.service;

import com.armada.task.model.entity.JoinTask;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JoinTaskIntervalPolicyTest {

    private final JoinTaskIntervalPolicy policy = new JoinTaskIntervalPolicy();

    @Test
    void nextExecuteAt_usesFixedRangeForModeOneAndIncludesUpperBound() {
        JoinTask task = task("FIXED_ACCOUNTS_PER_LINK", 3, 7, 20, 30);
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextLong(3, 8)).thenReturn(7L);

        assertThat(policy.nextExecuteAt(task, 10_000L, random)).isEqualTo(17_000L);
    }

    @Test
    void nextExecuteAt_usesMultiRangeAndEqualBounds() {
        JoinTask task = task("FIXED_ACCOUNT_MULTI_LINK", 3, 7, 5, 5);

        assertThat(policy.nextExecuteAt(task, 10_000L, mock(RandomGenerator.class))).isEqualTo(15_000L);
    }

    @Test
    void nextExecuteAt_rejectsNegativeReversedAndOverflow() {
        assertThatThrownBy(() -> policy.nextExecuteAt(
                task("FIXED_ACCOUNTS_PER_LINK", -1, 2, 1, 2), 0, mock(RandomGenerator.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.nextExecuteAt(
                task("FIXED_ACCOUNTS_PER_LINK", 3, 2, 1, 2), 0, mock(RandomGenerator.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.nextExecuteAt(
                task("FIXED_ACCOUNTS_PER_LINK", 1, 1, 1, 2), Long.MAX_VALUE, mock(RandomGenerator.class)))
                .isInstanceOf(ArithmeticException.class);
    }

    private static JoinTask task(String mode, int fixedMin, int fixedMax, int multiMin, int multiMax) {
        JoinTask task = new JoinTask();
        task.setDistributionMode(mode);
        task.setFixedIntervalMinSec(fixedMin);
        task.setFixedIntervalMaxSec(fixedMax);
        task.setMultiIntervalMinSec(multiMin);
        task.setMultiIntervalMaxSec(multiMax);
        return task;
    }
}
