package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PullTaskOperationDelayPolicyTest {

    @Test
    void acceptsInclusiveThreeAndFiveSecondBoundaries() {
        assertThat(new PullTaskOperationDelayPolicy(() -> 3_000L)
                .nextSideEffectAt(10_000L)).isEqualTo(13_000L);
        assertThat(new PullTaskOperationDelayPolicy(() -> 5_000L)
                .nextSideEffectAt(10_000L)).isEqualTo(15_000L);
    }

    @Test
    void rejectsDelaySupplierValuesOutsideTheContract() {
        assertThatThrownBy(() -> new PullTaskOperationDelayPolicy(() -> 2_999L)
                .nextSideEffectAt(10_000L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PullTaskOperationDelayPolicy(() -> 5_001L)
                .nextSideEffectAt(10_000L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maxDeadlineSamplesExactlyOnceAndKeepsTheLaterConstraint() {
        AtomicInteger samples = new AtomicInteger();
        PullTaskOperationDelayPolicy policy = new PullTaskOperationDelayPolicy(() -> {
            samples.incrementAndGet();
            return 4_000L;
        });

        assertThat(policy.maxDeadline(20_000L, 10_000L)).isEqualTo(20_000L);
        assertThat(samples).hasValue(1);
    }
}
