package com.armada.account.model.dto;

import java.math.BigDecimal;

/** 固定次数采购指令；requestId 在当前租户内幂等，数量不代表保证注册成功数。 */
public record AccountRegistrationCreateDTO(String requestId, String countryId, BigDecimal unitPrice,
        Integer quantity, Long accountGroupId, Integer accountType, String ipAllocationMode, String ipRegion,
        String providerId) { }
