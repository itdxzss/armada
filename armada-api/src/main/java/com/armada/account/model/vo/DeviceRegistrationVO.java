package com.armada.account.model.vo;

import java.math.BigDecimal;
import com.armada.account.model.enums.DeviceRegistrationFailureKind;

/** 单许可快照；验证码仅在 RECEIVED 时短暂返回，不持久化、不缓存。 */
public record DeviceRegistrationVO(String requestId, String state, String phoneNumber, String code,
        BigDecimal unitPrice, String countryId, long purchaseBefore, String failureCode,
        String providerId, String replacesRequestId, BigDecimal actualCost, Integer currency, int purchaseAttempts, Long nextPurchaseAt,
        DeviceRegistrationFailureKind failureKind, String failureDetail) {
    /** 避免默认 record 字符串泄露短信或号码。 */
    @Override public String toString() { return "DeviceRegistrationVO[state=" + state + "]"; }
}
