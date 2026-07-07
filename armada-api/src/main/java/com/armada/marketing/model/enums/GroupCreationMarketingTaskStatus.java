package com.armada.marketing.model.enums;

public enum GroupCreationMarketingTaskStatus {
    PENDING(1),
    RUNNING(2),
    SUCCESS(3),
    FAILED(4),
    PARTIAL_FAILED(5),
    STOPPED(6);

    private final int code;

    GroupCreationMarketingTaskStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
