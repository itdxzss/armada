package com.armada.promotion.channel.service;

/** Facebook CAPI 测试与正式事件共享的出站边界；实现不得记录 Token、匹配字段或原始响应。 */
public interface FacebookCapiClient {

    Result probe(ProbeCommand command);

    Result send(BusinessEventCommand command);

    record ProbeCommand(
            String trackingId,
            String accessToken,
            String testEventCode,
            String eventSourceUrl,
            String eventName,
            String eventId,
            long eventTimeSeconds,
            String externalId) {

        @Override
        public String toString() {
            return "ProbeCommand[trackingId=" + trackingId
                    + ", accessToken=<redacted>, testEventCode=<redacted>"
                    + ", eventName=" + eventName + ", eventId=" + eventId + "]";
        }
    }

    record BusinessEventCommand(
            String trackingId,
            String accessToken,
            String eventSourceUrl,
            String eventName,
            String eventId,
            long eventTimeSeconds,
            String phoneSha256,
            String clientIp,
            String clientUserAgent,
            String fbp,
            String fbc) {

        @Override
        public String toString() {
            return "BusinessEventCommand[trackingId=" + trackingId
                    + ", accessToken=<redacted>, eventName=" + eventName
                    + ", eventId=" + eventId + ", userData=<redacted>]";
        }
    }

    /**
     * @param success Meta 是否接收事件
     * @param retryable 是否适合由 Outbox 有界重试
     * @param errorCode 稳定脱敏错误码
     * @param errorMessage 截断前的脱敏错误摘要
     */
    record Result(boolean success, boolean retryable, String errorCode, String errorMessage) {
        public static Result accepted() {
            return new Result(true, false, null, null);
        }
    }
}
