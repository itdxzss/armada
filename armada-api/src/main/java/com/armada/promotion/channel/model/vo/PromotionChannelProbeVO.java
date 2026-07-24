package com.armada.promotion.channel.model.vo;

/**
 * 渠道 CAPI 探测结果；失败也返回完整详情供页面弹窗展示。
 *
 * @param success 是否探测成功
 * @param status 页面状态码：NORMAL 或 ABNORMAL
 * @param trackingId Pixel 或平台追踪 ID，可为空
 * @param accessTokenConfigured 是否存在完整 Token 密文配置
 * @param eventName 本次测试事件名，未发起时为空
 * @param eventId 本次测试事件 ID，未发起时为空
 * @param errorCode 脱敏错误码，成功时为空
 * @param errorMessage 脱敏错误摘要，成功时为空
 * @param probedAt 本次探测或失败判定时间，epoch 毫秒
 */
public record PromotionChannelProbeVO(
        boolean success,
        String status,
        String trackingId,
        boolean accessTokenConfigured,
        String eventName,
        String eventId,
        String errorCode,
        String errorMessage,
        long probedAt) {
}
