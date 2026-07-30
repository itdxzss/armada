package com.armada.promotion.pairing.model.enums;

import com.armada.promotion.channel.model.enums.FacebookStandardEvent;

/** 推广配对对应的三个正式 CAPI 业务阶段。 */
public enum PromotionCapiEventStage {
    LEAD(1, FacebookStandardEvent.LEAD.code()),
    LOGIN_REQUEST(2, FacebookStandardEvent.INITIATE_CHECKOUT.code()),
    LOGIN_SUCCESS(3, FacebookStandardEvent.COMPLETE_REGISTRATION.code());

    private final int code;
    private final String defaultEventName;

    PromotionCapiEventStage(int code, String defaultEventName) {
        this.code = code;
        this.defaultEventName = defaultEventName;
    }

    public int code() {
        return code;
    }

    public String defaultEventName() {
        return defaultEventName;
    }
}
