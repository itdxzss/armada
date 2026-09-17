package com.armada.account.model.dto;

import java.math.BigDecimal;

/** 控端配置一次手机取号许可；保存本身不取号，requestId 由客户端保持幂等。 */
public record DeviceRegistrationSetupDTO(String requestId, String deviceId, String countryId,
        BigDecimal unitPrice, String providerId, long expiresAt) { }
