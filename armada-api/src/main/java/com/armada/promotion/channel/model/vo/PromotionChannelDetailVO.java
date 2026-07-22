package com.armada.promotion.channel.model.vo;

/**
 * 渠道编辑回显数据。Access Token 只返回是否已配置，不返回明文、密文、指纹或密钥版本。
 */
public record PromotionChannelDetailVO(
        Long id,
        String channelName,
        Long ownerUserId,
        String targetCountry,
        Long landingTemplateId,
        String domain,
        String preselectedCountry,
        Integer platform,
        String trackingId,
        boolean accessTokenConfigured,
        String leadEventName,
        String loginRequestEventName,
        String loginSuccessEventName,
        boolean inAppOpenAllowed,
        boolean marketingAllowed,
        Integer status) {
}
