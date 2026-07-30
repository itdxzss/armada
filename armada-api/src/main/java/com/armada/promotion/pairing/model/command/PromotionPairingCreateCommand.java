package com.armada.promotion.pairing.model.command;

/** 公开入口创建推广配对会话的完整请求上下文。 */
public record PromotionPairingCreateCommand(
        String channelCode,
        String forwardedHost,
        String phone,
        String fbp,
        String fbc,
        String sourceUrl,
        String clientIp,
        String clientUserAgent) {

    @Override
    public String toString() {
        return "PromotionPairingCreateCommand[channelCode=" + channelCode
                + ", forwardedHost=" + forwardedHost + ", sensitive=REDACTED]";
    }
}
