package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 自动尝试预算同时约束未知和明确失败，波次冷却有界且可恢复。 */
class PullTaskRetryPolicyTest {

    @Test
    void automaticBudgetIncludesTheInitialAttempt() {
        assertThat(PullTaskRetryPolicy.canRetry(1)).isTrue();
        assertThat(PullTaskRetryPolicy.canRetry(3)).isTrue();
        assertThat(PullTaskRetryPolicy.canRetry(4)).isFalse();
        assertThat(PullTaskRetryPolicy.canRetry(9)).isFalse();
        assertThat(PullTaskRetryPolicy.canRetry(0)).isFalse();
    }

    @Test
    void retryWavesBackOffAndCapForHistoricalHighWaveNumbers() {
        assertThat(PullTaskRetryPolicy.retryDelayMs(1)).isEqualTo(60_000L);
        assertThat(PullTaskRetryPolicy.retryDelayMs(2)).isEqualTo(120_000L);
        assertThat(PullTaskRetryPolicy.retryDelayMs(3)).isEqualTo(240_000L);
        assertThat(PullTaskRetryPolicy.retryDelayMs(9)).isEqualTo(240_000L);
    }
}
