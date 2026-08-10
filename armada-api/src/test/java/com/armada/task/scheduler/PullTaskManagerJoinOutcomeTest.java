package com.armada.task.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PullTaskManagerJoinOutcomeTest {

    @Test
    void unavailableGroupFailureUsesDisplayableReason() {
        PullTaskManagerJoinOutcome outcome =
                PullTaskManagerJoinOutcome.executionFailed("GROUP_UNAVAILABLE");

        assertThat(outcome.reasonMessage()).isEqualTo("群不可用或已封禁");
    }
}
