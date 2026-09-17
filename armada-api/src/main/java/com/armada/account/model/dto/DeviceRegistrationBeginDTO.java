package com.armada.account.model.dto;

import java.math.BigDecimal;

/** 手机明确确认的一次取号意图；设备与租户由认证链绑定，重试沿用请求ID。 */
public record DeviceRegistrationBeginDTO(String requestId, String countryId, BigDecimal unitPrice) { }
