package com.armada.promotion.pairing.model.enums;

/** 推广正式 CAPI 事件 Outbox 状态。 */
public enum PromotionCapiEventStatus {
    WAITING(0),
    PENDING(1),
    LOCKED(2),
    SENT(3),
    DEAD(4),
    CANCELED(5);

    private final int code;

    PromotionCapiEventStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
