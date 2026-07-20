package com.armada.promotion.channel.model.vo;

/**
 * 渠道管理新增与分页统一出参。敏感 Token、密文、指纹和密钥版本永不返回。
 *
 * <p>{@code creatorUserId} 当前与 {@code ownerUserId} 相同；同时保留两个语义字段，便于前端按页面列名使用。</p>
 */
public record PromotionChannelVO(
        Long id,
        String channelName,
        String channelCode,
        Long ownerUserId,
        Long creatorUserId,
        Long targetCountryId,
        String targetCountryIso2,
        String targetCountryName,
        String targetCountryFlag,
        boolean mixedTargetCountry,
        Long landingTemplateId,
        String templateName,
        Integer platform,
        String platformName,
        String trackingStatus,
        String promotionLink,
        String splitLink,
        Long preselectedCountryId,
        String preselectedCountryIso2,
        String preselectedCountryName,
        String preselectedPhonePrefix,
        String preselectedCountryFlag,
        Integer status,
        Boolean inAppOpenAllowed,
        Boolean marketingAllowed,
        Long createdAt) {
}
