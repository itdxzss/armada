package com.armada.account.model.vo;

import java.math.BigDecimal;

/**
 * 运营可见的手动接码订单，含完整号码与验证码，供后台人工注册使用。
 * registrationState、registrationFailure 来自 Go 注册办理记录：订单没点过“开始注册”时为 null，
 * 办理未进入“需处理”时 registrationFailure 为空串。
 */
public record SmsActivationVO(Long id, String activationId, String phoneNumber, String countryId, BigDecimal cost,
        Integer currency, String state, String smsCode, Long receivedAt, Long cancelAfter, Long expiresAt,
        Long createdBy, Long createdAt, Long updatedAt, String registrationState, String registrationFailure,
        Integer registrationAccountType) {

    /** @return 附带注册办理状态的副本，其余字段不变 */
    public SmsActivationVO withRegistration(String registrationState, String registrationFailure,
            Integer registrationAccountType) {
        return new SmsActivationVO(id, activationId, phoneNumber, countryId, cost, currency, state, smsCode,
                receivedAt, cancelAfter, expiresAt, createdBy, createdAt, updatedAt, registrationState,
                registrationFailure, registrationAccountType);
    }
}
