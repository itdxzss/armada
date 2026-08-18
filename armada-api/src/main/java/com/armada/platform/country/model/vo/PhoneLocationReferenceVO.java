package com.armada.platform.country.model.vo;

/**
 * 按有效国际手机号推断的只读归属信息。
 *
 * @param country 严格按完整有效号码解析的启用国家
 * @param regionCode 原始号段分配区域代码；无法推断时可空
 * @param regionName 原始号段分配区域展示名；无法推断时可空
 */
public record PhoneLocationReferenceVO(
        CountryReferenceVO country,
        String regionCode,
        String regionName) {
}
