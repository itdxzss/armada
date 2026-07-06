package com.armada.marketing.service.impl;

final class MarketingTaskAccountStatusResolver {

    private static final int TARGET_STATUS_PENDING = 1;
    private static final int TARGET_STATUS_SUCCESS = 3;
    private static final int TARGET_STATUS_FAILED = 4;
    private static final int TARGET_STATUS_PARTIAL_FAILED = 5;

    private MarketingTaskAccountStatusResolver() {
    }

    static int resolve(Integer fallbackStatus, int sentMessageCount, int failedMessageCount) {
        boolean hasSuccess = sentMessageCount > 0;
        boolean hasFailed = failedMessageCount > 0;
        if (hasSuccess && hasFailed) {
            return TARGET_STATUS_PARTIAL_FAILED;
        }
        if (hasFailed) {
            return TARGET_STATUS_FAILED;
        }
        if (hasSuccess) {
            return TARGET_STATUS_SUCCESS;
        }
        return fallbackStatus == null ? TARGET_STATUS_PENDING : fallbackStatus;
    }
}
