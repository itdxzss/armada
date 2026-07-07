package com.armada.marketing.model.enums;

public enum GroupCreationMarketingItemStatus {
    PENDING(1),
    GROUP_CREATING(2),
    MARKETING_SENDING(3),
    SUCCESS(4),
    FAILED(5),
    ABANDONED(6);

    private final int code;

    GroupCreationMarketingItemStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == ABANDONED;
    }
}
