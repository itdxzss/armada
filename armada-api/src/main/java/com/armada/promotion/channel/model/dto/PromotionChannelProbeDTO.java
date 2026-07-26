package com.armada.promotion.channel.model.dto;

/**
 * Facebook CAPI 测试事件探测参数。
 *
 * @param testEventCode Meta Events Manager 生成的测试事件码；仅完整 Facebook 配置发起真实探测时必填
 */
public record PromotionChannelProbeDTO(String testEventCode) {
}
