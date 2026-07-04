package com.armada.marketing.model.enums;

public enum MarketingSendAttemptStatus {
    SUBMITTED(0),
    SUCCESS(1),
    FAILED(2),
    SKIPPED(3);

    private final int code;

    MarketingSendAttemptStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
