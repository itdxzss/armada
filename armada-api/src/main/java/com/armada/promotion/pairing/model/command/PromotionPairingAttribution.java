package com.armada.promotion.pairing.model.command;

/** 服务端校验后的 Meta CAPI 浏览器归因上下文。 */
public record PromotionPairingAttribution(
        String fbp,
        String fbc,
        String sourceUrl,
        String clientIp,
        String clientUserAgent) {

    @Override
    public String toString() {
        return "PromotionPairingAttribution[sensitive=REDACTED]";
    }
}
