package com.armada.account.model.dto;

import java.math.BigDecimal;

/**
 * 手动取号请求。
 * @param countryId 当前国家目录中的供应商国家 ID
 * @param maxPrice 本次可接受的最高单价，一般取所选价格档
 * @param providerId 可选的指定商家 ID；为空由平台按价格选择
 */
public record SmsActivationCreateDTO(String countryId, BigDecimal maxPrice, String providerId) { }
