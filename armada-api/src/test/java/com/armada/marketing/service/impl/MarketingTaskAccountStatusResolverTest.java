package com.armada.marketing.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingTaskAccountStatusResolverTest {

    private static final int TARGET_STATUS_PENDING = 1;
    private static final int TARGET_STATUS_SUCCESS = 3;
    private static final int TARGET_STATUS_FAILED = 4;
    private static final int TARGET_STATUS_PARTIAL_FAILED = 5;
    private static final int TARGET_STATUS_SKIPPED = 6;

    @Test
    void mixedSendResultsResolvePartialFailed() {
        int status = MarketingTaskAccountStatusResolver.resolve(TARGET_STATUS_PENDING, 2, 1);

        assertThat(status).isEqualTo(TARGET_STATUS_PARTIAL_FAILED);
    }

    @Test
    void onlyFailedResultsResolveFailed() {
        int status = MarketingTaskAccountStatusResolver.resolve(TARGET_STATUS_PENDING, 0, 2);

        assertThat(status).isEqualTo(TARGET_STATUS_FAILED);
    }

    @Test
    void onlySuccessfulResultsResolveSuccess() {
        int status = MarketingTaskAccountStatusResolver.resolve(TARGET_STATUS_PENDING, 3, 0);

        assertThat(status).isEqualTo(TARGET_STATUS_SUCCESS);
    }

    @Test
    void withoutSendRecordsKeepsFallbackTargetStatus() {
        int status = MarketingTaskAccountStatusResolver.resolve(TARGET_STATUS_SKIPPED, 0, 0);

        assertThat(status).isEqualTo(TARGET_STATUS_SKIPPED);
    }

    @Test
    void withoutSendRecordsDefaultsToPendingWhenFallbackMissing() {
        int status = MarketingTaskAccountStatusResolver.resolve(null, 0, 0);

        assertThat(status).isEqualTo(TARGET_STATUS_PENDING);
    }
}
