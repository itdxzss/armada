package com.armada.promotion.channel.model.dto;

/** 渠道域正式投递单条 Facebook CAPI 事件所需的脱敏命令。 */
public record PromotionChannelCapiEventDTO(
        Long channelId,
        String eventSourceUrl,
        String eventName,
        String eventId,
        Long eventTimeSeconds,
        String phoneSha256,
        String clientIp,
        String clientUserAgent,
        String fbp,
        String fbc) {

    @Override
    public String toString() {
        return "PromotionChannelCapiEventDTO[channelId=" + channelId
                + ", eventName=" + eventName + ", eventId=" + eventId
                + ", sensitive=REDACTED]";
    }
}
