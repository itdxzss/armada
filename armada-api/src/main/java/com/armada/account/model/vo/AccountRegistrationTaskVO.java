package com.armada.account.model.vo;

import java.math.BigDecimal;

/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationTaskVO(Long id, String requestId, String countryId, BigDecimal unitPrice, Integer quantity, Long accountGroupId, Integer accountType, String ipAllocationMode, String ipRegion, Boolean cancelRequested, String status, Long createdAt, Long updatedAt, AccountRegistrationCountsVO counts) { }
