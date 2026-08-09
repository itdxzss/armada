package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PullTaskPullerInviteDelayPolicyTest {

    @Test
    void acceptsInclusiveSixAndEightSecondBoundaries() {
        assertThat(new PullTaskPullerInviteDelayPolicy(() -> 6_000L)
                .nextInviteAt(10_000L)).isEqualTo(16_000L);
        assertThat(new PullTaskPullerInviteDelayPolicy(() -> 8_000L)
                .nextInviteAt(10_000L)).isEqualTo(18_000L);
    }

    @Test
    void rejectsDelaySupplierValuesOutsideTheContract() {
        assertThatThrownBy(() -> new PullTaskPullerInviteDelayPolicy(() -> 5_999L)
                .nextInviteAt(10_000L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PullTaskPullerInviteDelayPolicy(() -> 8_001L)
                .nextInviteAt(10_000L)).isInstanceOf(IllegalStateException.class);
    }
}
