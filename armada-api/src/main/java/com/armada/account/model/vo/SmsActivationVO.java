package com.armada.account.model.vo;

import java.math.BigDecimal;

/** 运营可见的手动接码订单，含完整号码与验证码，供后台人工注册使用。 */
public record SmsActivationVO(Long id, String activationId, String phoneNumber, String countryId, BigDecimal cost,
        Integer currency, String state, String smsCode, Long receivedAt, Long cancelAfter, Long expiresAt,
        Long createdBy, Long createdAt, Long updatedAt) { }
