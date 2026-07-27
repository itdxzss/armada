package com.armada.promotion.channel.model.vo;

/** 正式 Facebook CAPI 投递的脱敏结果。 */
public record PromotionChannelCapiDeliveryResult(
        boolean success,
        boolean retryable,
        String errorCode,
        String errorMessage) {
}
