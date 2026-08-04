package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PullTaskBatchSizeSelectorTest {

    @Test
    void fixedRangeUsesConfiguredCountAndFinalRemainderUsesAllRemaining() {
        PullTaskBatchSizeSelector selector = new PullTaskBatchSizeSelector((minimum, maximum) -> 3);

        assertThat(selector.select(3, 3, 10)).isEqualTo(3);
        assertThat(selector.select(3, 5, 2)).isEqualTo(2);
    }

    @Test
    void randomRangeIsInclusiveAndCappedByRemainingCount() {
        PullTaskBatchSizeSelector upper =
                new PullTaskBatchSizeSelector((minimum, maximum) -> maximum);
        PullTaskBatchSizeSelector lower =
                new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);

        assertThat(upper.select(2, 5, 4)).isEqualTo(4);
        assertThat(lower.select(2, 5, 4)).isEqualTo(2);
    }
}
