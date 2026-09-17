package com.armada.account.model.vo;

import java.math.BigDecimal;

/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationItemVO(Long id, Integer ordinal, String state, String activationId, String phoneNumber, BigDecimal actualCost, Integer currency, String registrationId, Long importBatchId, Long accountId, String failureCode, Long createdAt, Long updatedAt, int purchaseAttempts, Long nextPurchaseAt) { }
