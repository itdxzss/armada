package com.armada.account.model.dto;

import java.math.BigDecimal;

/** 服务端单次注册许可；可指定唯一商家及明确替代的已结束请求，HTTP 不能覆盖。 */
public record DeviceRegistrationPermit(long tenantId, String deviceId, String requestId,
        String countryId, BigDecimal unitPrice, long expiresAt, String providerId, String replacesRequestId) { }
