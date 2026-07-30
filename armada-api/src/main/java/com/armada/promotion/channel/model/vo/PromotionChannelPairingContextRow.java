package com.armada.promotion.channel.model.vo;

/** 公开配对入口按渠道码和访问域名解析出的可信业务上下文。 */
public record PromotionChannelPairingContextRow(
        Long tenantId,
        Long channelId,
        String channelName,
        Long ownerUserId,
        String preferredProxyRegion,
        Integer platform,
        String leadEventName,
        String loginRequestEventName,
        String loginSuccessEventName) {
}
