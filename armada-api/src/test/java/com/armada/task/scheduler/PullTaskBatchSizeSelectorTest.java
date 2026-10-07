package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PullTaskBatchSizeSelectorTest {

    @Test
    void fixedRangeUsesConfiguredCountAndFinalRemainderUsesAllRemaining() {
        PullTaskBatchSizeSelector selector = new PullTaskBatchSizeSelector((minimum, maximum) -> 3);

        assertThat(selector.select(50, 50, 100)).isEqualTo(50);
        assertThat(selector.select(50, 50, 2)).isEqualTo(2);
    }

    @Test
    void randomRangeIsInclusiveAndCappedByRemainingCount() {
        PullTaskBatchSizeSelector upper =
                new PullTaskBatchSizeSelector((minimum, maximum) -> maximum);
        PullTaskBatchSizeSelector lower =
                new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);

        assertThat(upper.select(2, 5, 4)).isEqualTo(4);
        assertThat(lower.select(2, 5, 4)).isEqualTo(2);
        assertThat(upper.select(1, 50, 100)).isEqualTo(50);
        assertThat(lower.select(1, 50, 100)).isEqualTo(1);
    }
}
